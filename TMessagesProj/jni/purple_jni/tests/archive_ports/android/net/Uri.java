package android.net;
public final class Uri {
 private final String value; private Uri(String value){this.value=value;}
 public static Uri parse(String value){return new Uri(value);}
 private java.net.URI parsed(){try{return new java.net.URI(value);}catch(Exception e){return java.net.URI.create("invalid:/");}}
 public String getScheme(){return parsed().getScheme();} public String getAuthority(){return parsed().getRawAuthority();}
 public java.util.List<String> getPathSegments(){String path=parsed().getRawPath();java.util.List<String> out=new java.util.ArrayList<>();if(path!=null)for(String part:path.split("/"))if(!part.isEmpty())out.add(part);return out;}
 public String getLastPathSegment(){java.util.List<String> p=getPathSegments();return p.isEmpty()?null:p.get(p.size()-1);}
 @Override public String toString(){return value;}
}
