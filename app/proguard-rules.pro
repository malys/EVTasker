# Contrat IPC avec MG4Control. Le Stub/Proxy AIDL est résolu par nom au moment du bind :
# le renommer casserait la liaison à l'exécution, sans erreur de compilation.
-keep interface com.mg4.control.api.IProfileControl { *; }
-keep class com.mg4.control.api.IProfileControl$* { *; }

# Gson (règles + historique).
#
# ATTENTION : ces règles ne suffisent PAS à elles seules, et l'ont prouvé sur la voiture.
# R8 a supprimé purement et simplement les sous-classes anonymes de TypeToken malgré le
# -keep ci-dessous ; chaque release lançait alors « TypeToken must be created with a type
# argument » et relisait zéro règle. La parade est dans le code : RuleStore et HistoryStore
# désérialisent en Array<T>::class.java, qui ne porte aucun générique. Ne pas réintroduire
# `object : TypeToken<List<X>>() {}` en comptant sur ces lignes.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Le format d'échange des règles : les NOMS DE CHAMPS de ces DTO sont les clés JSON du
# fichier écrit sur la clé USB. Sans cette règle R8 les renomme en a/b/c, et une release
# exporte un fichier qu'aucune version ne peut relire.
-keep class com.mg4.tasker.store.RuleTransfer$* { <fields>; <init>(...); }

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
