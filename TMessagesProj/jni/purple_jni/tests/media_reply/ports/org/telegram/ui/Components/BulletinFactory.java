package org.telegram.ui.Components;
public class BulletinFactory {
 public static int shown;
 public static BulletinFactory of(org.telegram.ui.ActionBar.BaseFragment view){return new BulletinFactory();}
 public BulletinFactory createErrorBulletin(String text){return this;}
 public void show(){++shown;}
}
