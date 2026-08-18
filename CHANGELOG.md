## 2026/8/17 v0.7.1 Minor fixes

- Users can now adjust the background color when exporting images. The default is transparent.
- Fixed an issue where crystals downloaded from Materials Project could not correctly apply space-group symmetry.
- Fixed an issue where crystals were sometimes not displayed after returning to the app from the background.
- Fixed an issue where editing a crystal while displaying it expanded by molecule left the viewer screen unrefreshed.
- Fixed an issue where abnormal chemical bonds were displayed when adding or editing atoms.
- Updated some code-level dependencies unrelated to user-facing functionality.

---

## 2026/8/7 v0.7.0 Major UI update and logic improvements

This release introduces a broad set of new features. Main changes:

### UI

- Removed Canvas rendering because it was outdated and provided a poor experience on phones.
- Added Preferences, allowing users to adjust the app's behavior to suit their habits.
- Reworked the Import from online sources page: the app can now filter search results more broadly, and MP searches have been improved. (A small number of crystals may still fail to display due to database issues.)
- Reworked the Preset crystal library page: users can now add groups and better manage and search crystallographic files stored in the app.
- A series of logic improvements and fixes.

### Logic improvements and new features

- **Hydrogen bonds**: With automatic bond rules enabled, the app automatically detects hydrogen bonds that meet the requirements and displays them by default. Their display can be adjusted in the Display menu.
- **Isotopic atoms**: The app now changes an atom's appearance when it is an isotope or is partially occupied.
- **Molecule detection**: The app now automatically detects molecular crystals and expands them by molecule for a better visual experience.
- This release introduces the spglib tool, improving the accuracy of unit-cell identification and conversion.

### Display

- Improved Filament atom materials and rendering.
- Changed the default atom colors from VESTA colors to CPK colors.
- When extending chemical bonds, secondary bonds between already displayed external atoms are now added. This can be disabled under Preferences -> Display.
- Fixed an issue where hydrogen-bond angle filtering incorrectly folded images back into periodic images. Only hydrogen bonds whose actual D-H...A image meets the angle threshold and has a covalent donor are now displayed.
- Fixed crashes on some Android GPUs when exporting images due to multisample offscreen readback. High-quality exports now use proportional supersampling.

---

## 2026/07/29 v0.6.5 Minor fixes and experience improvements

- Fixed the display issue when using the Follow system theme color option.
- Fixed several logic issues related to 3x3 matrix transformations. Added primitive-cell and conventional-cell conversions.
- Improved the COD database search experience. Exact matches are now shown first.
- Improved the smart_ionic rules. Reasonable bonds between anions are now allowed.
- Added a Remove symmetry option.
- Added more crystal examples and a search box.
- Added a Notes feature. Users can now add Krystals notes to crystal files for notes or comments.
- Various visual improvements.
