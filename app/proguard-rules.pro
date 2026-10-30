# RoundTrace ProGuard / R8 规则
# Debug 不混淆；Release 目前为粗放保留（P0 不做混淆优化，避免反射/Room/序列化误伤）。

# 保留行号，便于崩溃定位
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature

# kotlinx.serialization 生成的序列化器
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room 生成的实现类
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**
