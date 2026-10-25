package android.app;
public class Activity extends android.content.Context {
 public final Application app;public boolean finishing,destroyed;
 public Activity(Application a){app=a;}public Application getApplication(){return app;}
 public boolean isFinishing(){return finishing;}public boolean isDestroyed(){return destroyed;}
 @Override public Object getSystemService(String s){return app.getSystemService(s);}
}
