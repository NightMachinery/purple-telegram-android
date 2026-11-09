package org.telegram.messenger.purple;
import org.json.*;
public final class PurpleMediaReplyPresentationCases {
 static int checks;
 static void check(boolean value,String why){++checks;if(!value)throw new AssertionError(why);}
 static JSONObject policy(String set){PurpleAccountSettings.policy("[media_reply.private]\nvoice='off'\nround_follow_voice=false\nround='both'\n[media_reply.sets.Pick]\npeers=['user:17']\n"+set);return PurpleMediaReply.policy(0);}
 public static void main(String[] args)throws Exception {
  System.load(args[0]);JSONObject policy=policy("round_follow_voice=false\n"),row=policy.getJSONArray("sets").getJSONObject(0);
  check(PurpleMediaReplyPresentation.modeSelection(row,"round",true)==4,"missing round chooser selects inheritance");
  check(!PurpleMediaReplyPresentation.roundDisabled(policy,row,true),"explicit independent row permits round editing");
  check(PurpleMediaReply.resolved(0,"user:17",0).getInt("round")==3,"omitted independent round inherits Both");
  check(policy.getJSONArray("defaults").getJSONObject(0).getInt("effective_round")==3,"native effective private default displayed Both");
  check(PurpleMediaReplyPresentation.followSelection(row,true)==1,"unchecked follow remains explicit");
  policy=policy("");row=policy.getJSONArray("sets").getJSONObject(0);
  check(!PurpleMediaReplyPresentation.roundDisabled(policy,row,true),"inherited false follow does not disable editor");
  check(PurpleMediaReplyPresentation.followSelection(row,true)==2,"inherited follow chooser selects inheritance");
  check(PurpleMediaReply.resolved(0,"user:17",0).getInt("round")==3,"inherited independent round remains enabled");
  check(policy.getJSONArray("defaults").getJSONObject(1).getInt("effective_round")==0,"mixed chat types retain own defaults");
  policy=policy("round_follow_voice=false\nround='off'\n");row=policy.getJSONArray("sets").getJSONObject(0);
  check(PurpleMediaReplyPresentation.modeSelection(row,"round",true)==0,"explicit Off is not inheritance");
  check(PurpleMediaReply.resolved(0,"user:17",0).getInt("round")==0,"explicit Off matches actual policy");
  policy=policy("round_follow_voice=false\nround='received'\n");row=policy.getJSONArray("sets").getJSONObject(0);
  check(PurpleMediaReplyPresentation.modeSelection(row,"round",true)==2&&PurpleMediaReply.resolved(0,"user:17",0).getInt("round")==2,"independent edit and chooser agree");
  policy=policy("round_follow_voice=true\nround='received'\n");row=policy.getJSONArray("sets").getJSONObject(0);
  check(PurpleMediaReplyPresentation.roundDisabled(policy,row,true)&&PurpleMediaReply.resolved(0,"user:17",0).getInt("round")==0,"checked follow disables own mode and uses resolved voice");
  PurpleAccountSettings.policy("");policy=PurpleMediaReply.policy(0);row=policy.getJSONArray("defaults").getJSONObject(0);
  check(!policy.getBoolean("enabled")&&PurpleMediaReplyPresentation.roundDisabled(policy,row,false),"initial policy Off with follow checked");
  System.out.println("MEDIA_REPLY_PRESENTATION_CHECKS="+checks);
 }
}
