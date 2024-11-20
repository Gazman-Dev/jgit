-keep class * extends org.eclipse.jgit.nls.TranslationBundle {
    public <init>();
}

-keep class org.eclipse.jgit.transport.** { *; }
-keep class org.eclipse.jgit.internal.transport.** { *; }
-keep class org.eclipse.jgit.lib.** { *; }
-keep class org.eclipse.jgit.util.** { *; }
-keep class org.eclipse.jgit.api.** { *; }
-keepclassmembers class org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider { *; }

# If using any interfaces
-keep interface org.eclipse.jgit.transport.CredentialsProvider { *; }
-keep interface org.eclipse.jgit.transport.CredentialItem { *; }