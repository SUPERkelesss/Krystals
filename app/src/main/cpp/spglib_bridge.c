/*
 * spglib_bridge.c — JNI bridge exposing spglib to Krystals (app module).
 *
 * Flow: standardize to conventional cell (to_primitive=0, no_idealize=0), then
 * refine (spg_refine_cell) so downstream Krystals computation/rendering sees a
 * symmetrized conventional cell.
 *
 * Native signature (com.krystals.app.SpglibNative):
 *   int refineToConventional(
 *       double[] lattice,    // in/out: [a, b, c, alpha, beta, gamma] degrees
 *       double[] positions,  // in/out: fractional coords, flattened 3*n (capacity >= 3*8n)
 *       int[]    atomicNumbers, // in/out: per-site atomic number (capacity >= 8n)
 *       double   symprec,    // symmetry tolerance (Angstrom)
 *       int[]    sgOut)      // out[0]: re-determined space-group number (0 if unknown)
 *   Returns new site count on success; negative spglib error code on failure.
 *   Arrays are resized in place by the caller (passed with capacity >= 8*n).
 */
#include <jni.h>
#include <math.h>
#include <string.h>
#include "spglib.h"

/* Convert a,b,c,alpha,beta,gamma -> lattice matrix in spglib's convention.
 * spglib stores lattice[i][j] with i = Cartesian component, j = lattice-vector
 * index (i.e. lattice vectors are COLUMNS), which is the transpose of the
 * pymatgen/Krystals row-vector convention. Build row-vector first, then
 * transpose. */
static void params_to_lattice(const double *p, double lat[3][3]) {
    double ar = p[3] * M_PI / 180.0;
    double br = p[4] * M_PI / 180.0;
    double gr = p[5] * M_PI / 180.0;
    double ca = cos(ar), cb = cos(br), cg = cos(gr);
    double sg = sin(gr);
    double vf2 = 1.0 + 2.0 * ca * cb * cg - ca * ca - cb * cb - cg * cg;
    double row[3][3];
    row[0][0] = p[0]; row[0][1] = 0.0; row[0][2] = 0.0;
    row[1][0] = p[1] * cg; row[1][1] = p[1] * sg; row[1][2] = 0.0;
    row[2][0] = p[2] * cb;
    row[2][1] = p[2] * (ca - cb * cg) / sg;
    row[2][2] = p[2] * sqrt(vf2) / sg;
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++)
            lat[i][j] = row[j][i]; /* transpose: columns are lattice vectors */
}

/* Inverse: spglib lattice matrix (columns = lattice vectors) -> a,b,c,alpha,
 * beta,gamma (degrees). Transpose to row-vector form first. */
static void lattice_to_params(const double lat[3][3], double *p) {
    double row[3][3];
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++)
            row[i][j] = lat[j][i];
    double a = sqrt(row[0][0] * row[0][0] + row[0][1] * row[0][1] + row[0][2] * row[0][2]);
    double b = sqrt(row[1][0] * row[1][0] + row[1][1] * row[1][1] + row[1][2] * row[1][2]);
    double c = sqrt(row[2][0] * row[2][0] + row[2][1] * row[2][1] + row[2][2] * row[2][2]);
    double ca = (row[1][0] * row[2][0] + row[1][1] * row[2][1] + row[1][2] * row[2][2]) / (b * c);
    double cb = (row[0][0] * row[2][0] + row[0][1] * row[2][1] + row[0][2] * row[2][2]) / (a * c);
    double cg = (row[0][0] * row[1][0] + row[0][1] * row[1][1] + row[0][2] * row[1][2]) / (a * b);
    p[0] = a; p[1] = b; p[2] = c;
    p[3] = acos(ca) * 180.0 / M_PI;
    p[4] = acos(cb) * 180.0 / M_PI;
    p[5] = acos(cg) * 180.0 / M_PI;
}

JNIEXPORT jint JNICALL
Java_com_krystals_app_SpglibNative_refineToConventional(
    JNIEnv *env, jobject thiz,
    jdoubleArray latticeArr, jdoubleArray positionsArr, jintArray numbersArr,
    jint numAtoms, jdouble symprec, jintArray sgOut,
    jintArray rotationsOut, jdoubleArray translationsOut, jintArray opCountOut) {

    if (latticeArr == NULL || positionsArr == NULL || numbersArr == NULL) {
        return -1000;
    }
    jsize capacity = (*env)->GetArrayLength(env, numbersArr);
    jsize posLen = (*env)->GetArrayLength(env, positionsArr);
    /* numAtoms is the REAL site count (Kotlin passes capacity-sized arrays);
     * the array lengths only bound the capacity. */
    if (numAtoms <= 0 || capacity < numAtoms || posLen < 3 * numAtoms) return -1001;

    double *lattice = (*env)->GetDoubleArrayElements(env, latticeArr, NULL);
    double *positions = (*env)->GetDoubleArrayElements(env, positionsArr, NULL);
    jint *numbers = (*env)->GetIntArrayElements(env, numbersArr, NULL);

    if (lattice == NULL || positions == NULL || numbers == NULL) {
        if (lattice) (*env)->ReleaseDoubleArrayElements(env, latticeArr, lattice, JNI_ABORT);
        if (positions) (*env)->ReleaseDoubleArrayElements(env, positionsArr, positions, JNI_ABORT);
        if (numbers) (*env)->ReleaseIntArrayElements(env, numbersArr, numbers, JNI_ABORT);
        return -1002;
    }

    double lat[3][3];
    params_to_lattice(lattice, lat);

    /* spglib consumes lattice/position in place; it may reorder sites and change
     * the count (conventionalization can increase n, refinement re-derives sites). */
    int n1 = spg_standardize_cell(lat, (double(*)[3])positions, numbers, (int)numAtoms,
                                  0 /* to_primitive=0: keep conventional */,
                                  0 /* no_idealize=0: idealize */,
                                  symprec);
    if (n1 <= 0) {
        int err = (int)spg_get_error_code();
        (*env)->ReleaseDoubleArrayElements(env, latticeArr, lattice, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, positionsArr, positions, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, numbersArr, numbers, JNI_ABORT);
        return -err; /* negative spglib error code */
    }

    int n2 = spg_refine_cell(lat, (double(*)[3])positions, numbers, n1, symprec);
    if (n2 <= 0) {
        int err = (int)spg_get_error_code();
        (*env)->ReleaseDoubleArrayElements(env, latticeArr, lattice, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, positionsArr, positions, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, numbersArr, numbers, JNI_ABORT);
        return -err;
    }

    lattice_to_params(lat, lattice);

    /* Re-determine the space-group number from the refined cell (symmetry may
     * differ from the raw MP download), so the caller writes a truthful CIF. */
    if (sgOut != NULL && (*env)->GetArrayLength(env, sgOut) >= 1) {
        SpglibDataset *ds = spg_get_dataset(lat, (double(*)[3])positions, numbers, n2, symprec);
        if (ds != NULL) {
            jint out = ds->spacegroup_number;
            (*env)->SetIntArrayRegion(env, sgOut, 0, 1, &out);
            if (opCountOut != NULL && (*env)->GetArrayLength(env, opCountOut) >= 1) {
                jint ops = ds->n_operations;
                (*env)->SetIntArrayRegion(env, opCountOut, 0, 1, &ops);
                if (rotationsOut != NULL && translationsOut != NULL) {
                    jsize rotCap = (*env)->GetArrayLength(env, rotationsOut);
                    jsize traCap = (*env)->GetArrayLength(env, translationsOut);
                    if (rotCap >= ds->n_operations * 9 && traCap >= ds->n_operations * 3) {
                        (*env)->SetIntArrayRegion(env, rotationsOut, 0, ds->n_operations * 9, (jint *)ds->rotations);
                        (*env)->SetDoubleArrayRegion(env, translationsOut, 0, ds->n_operations * 3, (jdouble *)ds->translations);
                    }
                }
            }
            spg_free_dataset(ds);
        }
    }

    (*env)->ReleaseDoubleArrayElements(env, latticeArr, lattice, 0);
    (*env)->ReleaseDoubleArrayElements(env, positionsArr, positions, 0);
    (*env)->ReleaseIntArrayElements(env, numbersArr, numbers, 0);
    return n2;
}

/* refineToPrimitive — find the primitive cell (spg_find_primitive, no idealization),
 * then re-determine the space-group number from the primitive cell.
 * Signature mirrors refineToConventional; returns new site count or a negative
 * spglib error code. Arrays are capacity-sized by the caller (>= 8 * numAtoms). */
JNIEXPORT jint JNICALL
Java_com_krystals_app_SpglibNative_refineToPrimitive(
    JNIEnv *env, jobject thiz,
    jdoubleArray latticeArr, jdoubleArray positionsArr, jintArray numbersArr,
    jint numAtoms, jdouble symprec, jintArray sgOut,
    jintArray rotationsOut, jdoubleArray translationsOut, jintArray opCountOut) {

    if (latticeArr == NULL || positionsArr == NULL || numbersArr == NULL) {
        return -1000;
    }
    jsize capacity = (*env)->GetArrayLength(env, numbersArr);
    jsize posLen = (*env)->GetArrayLength(env, positionsArr);
    if (numAtoms <= 0 || capacity < numAtoms || posLen < 3 * numAtoms) return -1001;

    double *lattice = (*env)->GetDoubleArrayElements(env, latticeArr, NULL);
    double *positions = (*env)->GetDoubleArrayElements(env, positionsArr, NULL);
    jint *numbers = (*env)->GetIntArrayElements(env, numbersArr, NULL);

    if (lattice == NULL || positions == NULL || numbers == NULL) {
        if (lattice) (*env)->ReleaseDoubleArrayElements(env, latticeArr, lattice, JNI_ABORT);
        if (positions) (*env)->ReleaseDoubleArrayElements(env, positionsArr, positions, JNI_ABORT);
        if (numbers) (*env)->ReleaseIntArrayElements(env, numbersArr, numbers, JNI_ABORT);
        return -1002;
    }

    double lat[3][3];
    params_to_lattice(lattice, lat);

    int n1 = spg_find_primitive(lat, (double(*)[3])positions, numbers, (int)numAtoms, symprec);
    if (n1 <= 0) {
        int err = (int)spg_get_error_code();
        (*env)->ReleaseDoubleArrayElements(env, latticeArr, lattice, JNI_ABORT);
        (*env)->ReleaseDoubleArrayElements(env, positionsArr, positions, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, numbersArr, numbers, JNI_ABORT);
        return -err;
    }

    lattice_to_params(lat, lattice);

    if (sgOut != NULL && (*env)->GetArrayLength(env, sgOut) >= 1) {
        SpglibDataset *ds = spg_get_dataset(lat, (double(*)[3])positions, numbers, n1, symprec);
        if (ds != NULL) {
            jint out = ds->spacegroup_number;
            (*env)->SetIntArrayRegion(env, sgOut, 0, 1, &out);
            if (opCountOut != NULL && (*env)->GetArrayLength(env, opCountOut) >= 1) {
                jint ops = ds->n_operations;
                (*env)->SetIntArrayRegion(env, opCountOut, 0, 1, &ops);
                if (rotationsOut != NULL && translationsOut != NULL) {
                    jsize rotCap = (*env)->GetArrayLength(env, rotationsOut);
                    jsize traCap = (*env)->GetArrayLength(env, translationsOut);
                    if (rotCap >= ds->n_operations * 9 && traCap >= ds->n_operations * 3) {
                        (*env)->SetIntArrayRegion(env, rotationsOut, 0, ds->n_operations * 9, (jint *)ds->rotations);
                        (*env)->SetDoubleArrayRegion(env, translationsOut, 0, ds->n_operations * 3, (jdouble *)ds->translations);
                    }
                }
            }
            spg_free_dataset(ds);
        }
    }

    (*env)->ReleaseDoubleArrayElements(env, latticeArr, lattice, 0);
    (*env)->ReleaseDoubleArrayElements(env, positionsArr, positions, 0);
    (*env)->ReleaseIntArrayElements(env, numbersArr, numbers, 0);
    return n1;
}
