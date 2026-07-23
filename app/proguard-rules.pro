# Contrat IPC avec MG4Control. Le Stub/Proxy AIDL est résolu par nom au moment du bind :
# le renommer casserait la liaison à l'exécution, sans erreur de compilation.
-keep interface com.mg4.control.api.IProfileControl { *; }
-keep class com.mg4.control.api.IProfileControl$* { *; }

# Gson (règles + historique). Sans Signature, le type générique de
# TypeToken<List<Rule>> est effacé et la désérialisation rend des LinkedTreeMap :
# les règles disparaîtraient silencieusement au premier lancement d'une release minifiée.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
-keep class * extends com.google.gson.reflect.TypeToken

# Les modèles sérialisés sont lus par réflexion : ne pas renommer leurs champs.
# Les enums en particulier sont persistés par NOM — les obfusquer rendrait illisible
# tout fichier de règles écrit par une version précédente.
-keep class com.mg4.tasker.model.** { *; }
-keepclassmembers class com.mg4.tasker.model.** {
    <fields>;
    <init>(...);
}
-keepclassmembers enum com.mg4.tasker.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Traces exploitables dans les rapports d'erreur.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
