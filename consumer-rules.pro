# Explicitly keep JGitText and its constructor
-keep class org.eclipse.jgit.internal.JGitText {
    <init>();
    *;
}

# Suppress warnings for JGit
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn javax.enterprise.**