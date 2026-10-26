package org.telegram.messenger.purple;

public final class PurpleRpcRetry {
    public enum Kind { Flood, Server, Transport, Timeout }
    public static final class Transient {
        public final Kind kind;
        public final long retryAt;
        public final boolean shortRetry;
        Transient(Kind kind,long retryAt,boolean shortRetry){this.kind=kind;this.retryAt=retryAt;this.shortRetry=shortRetry;}
    }
    public static final class Budget {
        private final long timeout;
        private long deadline;
        private boolean shortUsed;
        private int failures;
        public Budget(long now,long timeout){if(timeout<=0)throw new IllegalArgumentException();this.timeout=timeout;deadline=add(now,timeout);}
        public long deadline(){return deadline;}
        public void resume(long now,Transient outcome){if(!outcome.shortRetry)deadline=add(now,timeout);}
        public Transient failed(long now,int code,String type,boolean ownTimeout){
            Long seconds=floodSeconds(type);
            if(seconds!=null){
                long wait=add(multiply(seconds,1000),1000);
                boolean shortRetry=!shortUsed&&seconds<=30&&now<=deadline&&wait<=deadline-now;
                if(shortRetry){shortUsed=true;deadline=add(deadline,wait);}
                return new Transient(Kind.Flood,add(now,shortRetry?wait:Math.max(1000,multiply(seconds,1000))),shortRetry);
            }
            Kind kind=ownTimeout?Kind.Timeout:code>=500&&code<600||"INTERNAL".equals(type)||"INTERNAL_SERVER_ERROR".equals(type)?Kind.Server:code<0?Kind.Transport:null;
            if(kind==null)return null;
            long backoff=5000L<<Math.min(failures++,4);
            return new Transient(kind,add(now,Math.min(backoff,60000)),false);
        }
    }
    static Long floodSeconds(String type){
        if(type==null)return null;
        String prefix=type.startsWith("FLOOD_WAIT_")?"FLOOD_WAIT_":type.startsWith("FLOOD_PREMIUM_WAIT_")?"FLOOD_PREMIUM_WAIT_":null;
        if(prefix==null)return null;
        String digits=type.substring(prefix.length());if(digits.isEmpty())return null;
        for(int i=0;i<digits.length();i++)if(digits.charAt(i)<'0'||digits.charAt(i)>'9')return null;
        try{return Long.parseLong(digits);}catch(NumberFormatException error){return Long.MAX_VALUE;}
    }
    static long add(long value,long delta){return delta>0&&value>Long.MAX_VALUE-delta?Long.MAX_VALUE:value+delta;}
    static long multiply(long value,long factor){return value>Long.MAX_VALUE/factor?Long.MAX_VALUE:value*factor;}
    private PurpleRpcRetry(){}
}
