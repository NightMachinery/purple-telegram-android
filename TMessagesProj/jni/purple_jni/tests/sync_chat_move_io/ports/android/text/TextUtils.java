package android.text;

public final class TextUtils {
    public static boolean isEmpty(CharSequence value){return value==null||value.length()==0;}
    public static boolean equals(CharSequence a,CharSequence b){return a==b||a!=null&&b!=null&&a.toString().equals(b.toString());}
    public static String join(CharSequence separator,Iterable<?> values){StringBuilder out=new StringBuilder();boolean first=true;for(Object value:values){if(!first)out.append(separator);out.append(value);first=false;}return out.toString();}
}
