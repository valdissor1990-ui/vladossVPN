# Copyright, distribution and licensing policy — VLADOSSVPN

**Status: draft for legal review. This document is not legal advice and does not certify ownership or compliance.**

## 1. What the repository currently says

- The repository contains a top-level `LICENSE` with GNU GPL version 3.
- The application packages `libbox.aar` from `Leadaxe/sing-box-lx` v`1.14.3-lx.23`; that upstream project states that it inherits GPL-3.0 from sing-box.
- `THIRD_PARTY_NOTICES.md`, bundled license assets, the in-app **Open Source Licenses** screen, and CI license checks are already present in the current source tree.
- The release workflow pins the libbox release and checks its SHA-256. This establishes the intended artifact identity, not the ownership of every line of source code.

## 2. Can VLADOSSVPN prohibit all copying and redistribution?

Not while distributing the GPL-covered application in its current form under the current licensing arrangement. GPLv3 expressly grants recipients permission to copy, modify, and redistribute the covered work subject to its conditions. A blanket term saying that no one may copy or redistribute the whole app without separate permission would conflict with those rights and should not be added to the GPL-covered application.

The project can still assert copyright in code it owns, require compliance with the applicable license, and reserve rights that the GPL does not grant, such as rights in the VLADOSSVPN name, logo, and branding. The GPL does not itself grant trademark rights. Any claim over a particular mark must be supported by actual ownership and applicable law.

## 3. What this audit can and cannot conclude

The reviewed repository identifies public upstream projects and license files for the VPN core and other bundled dependencies. Public availability alone does not mean that code is public domain; each applicable license and notice must be followed.

No evidence of stolen code was identified in this limited repository/configuration review. That is **not** a certification that no copied code is unauthorized. A conclusive provenance review would require examining the full Git history, authorship and contribution permissions, all source and binary dependencies, generated code, and the exact source corresponding to every distributed build.

## 4. Required distribution hygiene

Before publishing each release:

1. Keep the GPLv3 text and all applicable third-party copyright, license, attribution, and notice files with the distributed application.
2. Make the corresponding source and required build/install information available as required by GPLv3 for the actual distribution method. A source-code URL alone should not be assumed sufficient in every distribution scenario.
3. Keep the in-app **Open Source Licenses** screen and verify that the actual APK contains the license texts and inventories checked by CI.
4. Preserve upstream notices and identify modified versions where required.
5. Do not claim ownership of third-party code, and do not add terms that restrict recipients' GPL rights.
6. Record the actual copyright holder(s) for original project contributions and obtain written permission or an appropriate contributor agreement for contributions whose ownership/licensing authority is unclear.

## 5. If an exclusively proprietary/no-redistribution app is required

Do not merely replace the top-level `LICENSE` or add a “no copying” notice. First obtain a separate license from the relevant rights holder(s) that permits the intended proprietary distribution, or replace GPL-covered components with alternatives whose licenses allow the intended model. Then audit every transitive/native dependency and establish that the project has the rights to relicense its own contributions. This requires a separate legal and technical review.

## 6. Branding notice (proposed)

The GPL license for the software does not, by itself, grant permission to use the VLADOSSVPN name, logo, or branding to imply that a redistributed build is official or endorsed by the project. Such use may require permission from the relevant rights holder, subject to applicable law. This notice must not be used to restrict copying, modification, or redistribution rights granted by GPLv3.

## 7. Items to confirm with the project owner

- Legal name of the copyright holder or rights-holding entity.
- Which original files are solely owned by that holder and whether all contributors granted compatible rights.
- Whether VLADOSSVPN branding/logo is owned by that holder and whether trademark registration or other evidence exists.
- Which exact source revision corresponds to each public APK/AAB and whether the corresponding source is available in the form required by GPLv3.
- Target jurisdictions and distribution channels (direct APK, Google Play, or both).
- Whether a proprietary model is desired; if so, evaluate replacement/licensing options for libbox before changing the license.

