package org.telegram.ui.Components;
import java.util.function.LongConsumer;
public class AlertsCreator {
 public static boolean defer;
 public static LongConsumer pending;
 public static void ensurePaidMessageConfirmation(int a,long d,int n,LongConsumer c,long stars){if(defer)pending=c;else c.accept(stars);}
 public static void approve(){LongConsumer c=pending;pending=null;c.accept(1);}
}
