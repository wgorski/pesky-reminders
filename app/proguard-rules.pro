# App-specific R8 rules. The defaults (proguard-android-optimize.txt) plus the
# rules AAPT generates for everything the manifest names — the activities and the
# two receivers — cover this app: nothing is reached by reflection, and the
# stored JSON is written and read by hand through org.json, which is part of the
# platform rather than the APK.
#
# Enum names are persisted (RepeatUnit.name in each task's "rule"), and R8 keeps
# them: an enum whose name() is used is never unboxed, and the name is the string
# literal handed to its constructor, which renaming does not touch.
