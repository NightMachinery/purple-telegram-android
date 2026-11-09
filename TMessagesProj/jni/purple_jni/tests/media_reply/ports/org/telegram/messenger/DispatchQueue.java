package org.telegram.messenger;public class DispatchQueue {public boolean postRunnable(Runnable r){AndroidUtilities.runOnUIThread(r);return true;}}
