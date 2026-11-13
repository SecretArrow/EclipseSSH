# Linux rootfs catalog research — the 16-distro survey (2026-11-13)

Question answered: which of the 16 distros proposed for the catalog can actually be pinned and
installed by this app today, and what exactly blocks the rest. Every URL below was HEAD-verified
from a clean sandbox on 2026-11-13; every checksum marked **verified** was confirmed byte-for-byte
against a full download, and the rest are quoted verbatim from the publisher's own checksum file
(or, where only a proot-distro plugin publishes one, from that plugin's pinned release artifact).

The catalog's contract (see `LinuxDistroCatalog.kt`): an entry carries an exact HTTPS URL, a
per-file SHA256 pin, and an approximate size — the install verifies the hash before extracting a
byte. An entry the pipeline cannot set up must not be added: a wrong family/manager pair is
caught at setup, not at install time. That contract is what drives every verdict below.

## The app's two hard constraints, discovered by this survey

1. **Codec:** `RootfsArchive` opens gzip streams only. xz (Debian/Deepin/Adélie/openSUSE/Void/
   Rocky-OCI/Fedora-OCI repackages) and bzip2 (Trisquel) need a new decompressor (e.g.
   `org.tukaani:xz`) before any of those tarballs can be extracted.
2. **Setup pipeline:** the install flow is Ubuntu-apt end-to-end (`UbuntuDistributionManager`
   writes the sources.list, registers the user, runs the health checks). `DistroFamily` and
   `PackageManager` are catalog metadata with no routing behind them yet — `ApkSetup` appears in
   KDoc but no apk/dnf/pacman/xbps/zypper pipeline exists. A non-apt rootfs installs its bytes
   but the app cannot configure or drive its package manager.

## Verdicts

| # | Distro | Verdict | Evidence (arm64) |
|---|--------|---------|------------------|
| 1 | Adélie | ROADMAP | No official rootfs (ISO-only). Pinnable Termux rebuild `proot-distro/releases/download/v4.19.0/adelie-aarch64-pd-v4.19.0.tar.xz` sha256 `b573ad9f6a3f6ef72d32a40378aed011ea5828e3f0a99ad9b7b0dabcb9f35028` **verified** (7,455,912 B, xz, wrapper dir). apk, own repos. Needs: xz + wrapper strip + apk pipeline. |
| 2 | Alpine | ALREADY IN CATALOG | 3.19/3.20/3.21 × 3 arches pinned from dl-cdn `.sha256`. Setup pipeline note: apk path not wired yet (see constraint 2). |
| 3 | AlmaLinux 10 | NO | No rootfs tarball published anywhere: `10/{docker,container,rootfs}/aarch64/` 404, cloud = qcow2 only, container images registry-only (OCI token flow). |
| 4 | Arch Linux ARM | NO | `ArchLinuxARM-aarch64-latest.tar.gz` is rolling-latest (791 MB), publishes **md5 only** (no sha256), HTTPS cert broken — http-only. Violates the SHA256-pin contract. |
| 5 | Artix | NO | Official media is x86_64 ISO-only; only aarch64 artifact is a 2022-era Termux rebuild (stale). pacman. |
| 6 | Chimera | ROADMAP (best next candidate) | Official dated snapshot, **gzip**, plain rootfs, minisig-signed sha256sums.txt: `https://repo.chimera-linux.org/live/20261103/chimera-linux-aarch64-ROOTFS-20261103-full.tar.gz` sha256 `a15cbcae55626748dfdcb9184b5e03d14f65a042435320dde70b7d7c890ba098` (194,989,229 B); bootstrap variant `2a8fc92d4cab69469ee759e662801ac783f9f26ed8ef6e52578ab2a9d31901a8` (8,002,684 B). Needs: apk pipeline for Chimera's own apk repos. |
| 7 | Debian Trixie | NO (as Trixie) | No official plain rootfs: cloud images are disk images (`disk.raw`), debuerreotype artifacts are OCI blobs. Nearest pinnable = Termux **bookworm** rebuild `v4.17.3/debian-bookworm-aarch64-pd-v4.17.3.tar.xz` sha256 `3a841a794ae5999b33e33b329582ed0379d4f54ca62c6ce5a8eb9cff5ef8900b` **verified** (42,912,980 B, xz, wrapper dir) — frozen content, "Debian 12" not Trixie. |
| 8 | Deepin | ROADMAP | No official rootfs (ISO-only). Termux rebuild `v4.16.0/deepin-aarch64-pd-v4.16.0.tar.xz` sha256 `620eb208b75fe13705da3a2ef4bbb71eedae0ce90ad7fdca3566eba361c8d5f6` **verified** (51,744,512 B, xz, wrapper dir). apt with deepin's own repos inside. Needs: xz + wrapper strip + repo-URL override in setup. |
| 9 | Fedora 43 | ROADMAP | Plain-rootfs option exists as the `.wsl` artifact (gzip! versioned 43-1.6, signed CHECKSUM): `download.fedoraproject.org/pub/fedora/linux/releases/43/Container/aarch64/images/Fedora-WSL-Base-43-1.6.aarch64.wsl` sha256 `7eef7a83260218d8c878b3c7bbdaf11772103145184d0c65df27557f4cd49548` (146,930,779 B) — but ships WSL config extras; the container OCI (65,936,860 B, xz, layered `blobs/sha256/`) is what proot-distro uses. Needs: dnf pipeline (+ OCI-layer extraction for the OCI variant). |
| 10 | Manjaro ARM | NO | Plain rootfs exists (`github.com/manjaro-arm/rootfs/releases/download/20261102/Manjaro-ARM-aarch64-latest.tar.gz`, 211,568,965 B, gzip) but the release ships **no checksum file** — nothing to pin. pacman. |
| 11 | openSUSE Leap 16.0 | ROADMAP | `download.opensuse.org/distribution/leap/16.0/appliances/opensuse-leap-image.aarch64-1.0.0-networkd-Build18.93.tar.xz` sha256 `21cb390d0cb3d3840a54b594fe02fae18bf4a4b2868ba01e26371c56990ed650` (31,439,532 B, xz, plain rootfs, +signed .asc). Needs: xz + zypper pipeline. |
| 12 | Pardus | NO | amd64 ISO-only; no arm64 anything. |
| 13 | Rocky Linux 10 | NO | Container images are OCI-layered only (`blobs/sha256/`, xz) — Rocky 10 dropped plain tars. sha256 `995350a80651f2867e399196288d17704f13b1035fb78e4bf56ba74a2d7775d7` (84,677,224 B) published+signed, but not extractable as a plain rootfs. |
| 14 | Trisquel | ROADMAP | arm64 rootfs exists, versioned, own `.sha256` published: `cdimage.trisquel.info/trisquel-images/trisquel-base_12.0_arm64.tar.bz2` sha256 `a241899069ca300eb54fadb06a1107f9d218add00d5f76387a8121ba1f23bf46` (460,203,734 B) — but **bzip2** (third codec) and huge; apt = already supported. |
| 15 | Ubuntu 25.10 | **ADDED** | `cdimage.ubuntu.com/ubuntu-base/releases/25.10/release/ubuntu-base-25.10-base-<arch>.tar.gz`; SHA256SUMS verbatim: amd64 `febb74fc4e09b2d84ed8655a9e25b55777cdb970f8eb8e6dee302676b519bc95`, arm64 `11534a7d4d94db63e7c65d6012b8696abdd6cf14b4efde01c6b1da196b77bc3b` (**verified** full download, 35,157,817 B), armhf `b830eca311f03e0514048bfb578bddb885d374be275299bf6391c53fbd6c5a27`. Plain rootfs, no wrapper, apt. Interim interim-name caveat: no point-suffixed tarball — file built from the bare version. |
| 16 | Void | ROADMAP | `repo-default.voidlinux.org/live/current/void-aarch64-ROOTFS-20250202.tar.xz` sha256 `01a30f17ae06d4d5b322cd579ca971bc479e02cc284ec1e5a4255bea6bac3ce6` (45,789,416 B, xz, plain rootfs, dated snapshot + published `sha256sum.txt`). Needs: xz + xbps pipeline. |

## Reference repos studied

- `termux/proot-distro`: classic bash plugins (URL + embedded per-arch SHA256) live under
  `distro-plugins/` up to tag v4.38.0; v5.x rewrote the project in Python and installs from OCI
  registries, publishing no rootfs assets. Its own release artifacts (`*-pd-vX.tar.xz`,
  xz, wrapped in `<distro>-<arch>/`) are Termux *rebuilt* rootfs files, not distro-published ones —
  usable pins, but third-party.
- `AirysDark/proot-distro-termux`: byte-identical v4.38.0 mirror; re-hosts artifacts on easycli.sh
  which 403s non-browser clients — effectively unpinnable from this app.
- `spsforks/termux-proot-distro`: older vintage (Fedora 42, Rocky 9.5, Tumbleweed, noble) but its
  plugin checksums point at reachable `termux/proot-distro` release artifacts — useful cross-check.
- `AndronixApp/andronix`: self-built rootfs on its own CDN (`dl.andronix.app`) with its own CI
  checksums — third-party rebuilds, same category as Termux's.

## Suggested order of future work

1. **xz support** in `RootfsArchive` (+ wrapper-dir strip for Termux artifacts) — unlocks
   Debian-bookworm/Deepin/Adélie/openSUSE/Void pins already verified above.
2. **apk pipeline** (repos per family: Alpine dl-cdn vs Chimera repo.chimera-linux.org vs Adélie
   distfiles) — unlocks Chimera (official gzip rootfs, strongest next candidate) and Alpine setup.
3. **dnf/zypper/xbps pipelines** — Rocky-OCI/Fedora/openSUSE/Void; OCI-layer extraction for the
   registry-only images (Alma).
4. Skip permanently unless upstream changes: Arch ARM (rolling + md5-only), Pardus, Artix,
   AlmaLinux (no tarball), Rocky 10 (OCI-only), Debian Trixie (no plain rootfs).
