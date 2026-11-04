package org.telegram.ui.Components;
import java.util.function.LongConsumer;
public class AlertsCreator {
 public static boolean defer, starsNeeded;
 public static LongConsumer starsPending;
 public static LongConsumer pending;
 public static void ensurePaidMessageConfirmation(int a,long d,int n,LongConsumer c,long stars){if(defer)pending=c;else c.accept(stars);}
 public static void approve(){LongConsumer c=pending;pending=null;if(starsNeeded)starsPending=c;else c.accept(1);}
}
