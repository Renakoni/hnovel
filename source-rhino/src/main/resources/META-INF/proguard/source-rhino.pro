# Rhino 1.8.1 loads these implementations through Kit.classOrNull/newInstanceOrNull.
# ScriptRuntime also resolves these type constants by name when matching init signatures.
-keep,allowoptimization class org.mozilla.javascript.Context,org.mozilla.javascript.ContextFactory,
    org.mozilla.javascript.ScriptableObject
-keep,allowoptimization interface org.mozilla.javascript.Function

-keep class org.mozilla.javascript.jdk18.VMBridge_jdk18 {
    public <init>();
}
-keep class org.mozilla.javascript.Interpreter {
    public <init>();
}
-keep class org.mozilla.javascript.regexp.RegExpImpl {
    public <init>();
}

# initSafeStandardObjects registers these by class name; LazilyLoadedCtor invokes init.
-keep class org.mozilla.javascript.regexp.NativeRegExp {
    public static void init(org.mozilla.javascript.Context, org.mozilla.javascript.Scriptable, boolean);
}
-keep class org.mozilla.javascript.NativeContinuation {
    public static void init(org.mozilla.javascript.Context, org.mozilla.javascript.Scriptable, boolean);
}
-keepclasseswithmembers class org.mozilla.javascript.typedarrays.** {
    public static void init(org.mozilla.javascript.Context, org.mozilla.javascript.Scriptable, boolean);
}

# ScriptDom dispatches only its existing allowlist, using Class.getMethods and Method.invoke.
# Keep the public data methods on those facade types, including inherited interface methods.
-keepclassmembers class org.jsoup.nodes.**,
    org.jsoup.parser.Parser,org.jsoup.parser.ParseSettings,org.jsoup.parser.ParseError,
    org.jsoup.parser.Tag,org.jsoup.select.Elements,
    okhttp3.Response,okhttp3.ResponseBody,okhttp3.Headers,okhttp3.Request,
    okhttp3.HttpUrl,okhttp3.MediaType,okio.ByteString {
    public <methods>;
}
-keepclassmembers interface org.jsoup.Connection$Base,org.jsoup.Connection$Response,
    org.jsoup.Connection$KeyVal,okio.BufferedSource,okio.Source {
    public <methods>;
}
# ScriptDom excludes overloads taking this type by its binary name.
-keepnames class org.jsoup.select.Evaluator
