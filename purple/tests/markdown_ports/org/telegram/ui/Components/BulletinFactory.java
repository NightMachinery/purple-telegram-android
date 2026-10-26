package org.telegram.ui.Components;
public final class BulletinFactory {
 public static int errors,copies;
 public static BulletinFactory of(org.telegram.ui.ActionBar.BaseFragment fragment){return new BulletinFactory();}
 public Bulletin createCopyBulletin(String text){copies++;return new Bulletin();}
 public Bulletin createErrorBulletin(CharSequence text){errors++;return new Bulletin();}
}
