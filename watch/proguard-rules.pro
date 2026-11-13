# Health Services (and other protobuf-lite libraries) find message fields by name at runtime,
# so R8 must not rename or remove them. Without this the app crashes when heart-rate
# readings start.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
