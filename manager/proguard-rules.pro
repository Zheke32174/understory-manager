# Manager app R8 rules.
#
# The shared modules ship their own consumer-rules.pro (kept via the AAR/module
# consumer proguard config): :elevation keeps the Shizuku UserService + AIDL
# stubs and the Elevation/Outcome public API; :common-security keeps its own
# surface. Nothing app-specific needs keeping here yet — the Manager is a
# rootless consumer with no reflectively-loaded classes of its own.
#
# Keep the Compose + Kotlin metadata the default optimized config already
# preserves; this file only adds app-local exceptions as they arise.
