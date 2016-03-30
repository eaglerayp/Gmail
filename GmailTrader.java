package com.company;

import javax.mail.*;
import javax.mail.search.AndTerm;
import javax.mail.search.FlagTerm;
import javax.mail.search.SearchTerm;
import java.io.*;
import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Yuan on 2015/5/18.
 * latest version 2016/02/16.
 */
public class GmailTrader {
    final static String version="20160330 two MT4 trader";
    final static Pattern ACTUALPattern = Pattern.compile("ACTUAL\\s*:\\s*(\\-*\\d+.\\d+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final static Pattern SurveyPattern = Pattern.compile("SURVEY\\s*:\\s*(\\-*\\d+.\\d+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final static Pattern PriorPattern = Pattern.compile("PRIOR\\s*:\\s*(\\-*\\d+.\\d+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final static Pattern subjec_before_tPattern = Pattern.compile("\\*RELEASE IN (\\d+) Hours*\\* (.+) \\{US\\}", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    final static Pattern subject_announce_Pattern = Pattern.compile("(.*) (UP|FALLS|DOWN|RISES|UNCHANGED)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    static Matcher ACTUALMatcher;
    static Matcher SURVEYMatcher;
    static Matcher PriorMatcher;
    static DateFormatSymbols symbols= new DateFormatSymbols( new Locale("en", "US"));
    static SimpleDateFormat sdff = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss",symbols);
    final static String better="better";
    final static String noaction="noaction";
    final static String worse="worse";
    static int PreventOtherEvent=0;
    static String subject="";
    final static long sixtymin_in_ms=3600000; //60min
    final static long thirtymin_in_ms=1800000; //30min
    //    final static long twomin_in_ms=120000; //2min
    final static long fifteen_in_ms=900000; //15min
    static boolean inprestage=false;
    static boolean inAnnouncestage=false;
    static HashMap<String,double[]> before_winrate_table= new HashMap<String, double[]>();
    static HashMap<String,double[]> after_winrate_table= new HashMap<String, double[]>();
    // (3) create a search term for all "unseen" messages
    static Flags seen = new Flags(Flags.Flag.SEEN);
    static FlagTerm unseenFlagTerm = new FlagTerm(seen, false);

    // (4) create a search term for all recent messages
    static Flags recent = new Flags(Flags.Flag.RECENT);
    static FlagTerm recentFlagTerm = new FlagTerm(recent, false);
    static SearchTerm searchTerm = new AndTerm(unseenFlagTerm, recentFlagTerm);

    static Properties email_props= new Properties();
    static Properties config_props;
    static Session session;
    static String EUR="EUR";
    static String GBP="GBP";
    static String AUD="AUD";
    static int prestage_wait_hour;
    static String event="";
    static int pre_index;
    static String user;
    static String pw;
    static String host;
    static String pre_event="";
    static double trade_threshold;
    static double trade_threshold_class;

    public static void main(String args[])  {
        System.out.println("version:"+version);
        config_props = new Properties();
        String configFile = "GmailTrader.properties";
        try {
            config_props.load(new FileInputStream(configFile));
        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
            return;
        } catch (IOException ex) {
            ex.printStackTrace();
            return;
        }
        String EURpath=config_props.getProperty("PATHEUR");
        String AUDpath=config_props.getProperty("PATHAUD");
        String file_extension_name=".txt";
        HashMap<String,File> cmdFiles=new HashMap<String, File>();
        File cmdfileEUR=new File(EURpath+config_props.getProperty(EUR)+file_extension_name);
//        File cmdfileGBP=new File(MT4path+config_props.getProperty(GBP)+file_extension_name);
        File cmdfileAUD=new File(AUDpath+config_props.getProperty(AUD)+file_extension_name);
        cmdFiles.put(AUD,cmdfileAUD);
        cmdFiles.put(EUR,cmdfileEUR);
//        cmdFiles.put(GBP,cmdfileGBP);

        // read win ration file, and construct table
        File before_file=new File(config_props.getProperty("BEFORE"));
        File after_file=new File(config_props.getProperty("AFTER"));
        File index_file=new File(config_props.getProperty("INDEX"));


        boolean initSuccess=false;
        while(!initSuccess) {
            try {
            initial_table(before_file,after_file,index_file);
//                initial_PL_table(before_file, after_file);
                System.out.println("winrate_table size:"+before_winrate_table.size());
                if (before_winrate_table.size()==128){
                    initSuccess=true;
                }
            } catch (IOException e) {
                System.out.println(e + "file error");
            }
        trade_threshold=0.5;
        trade_threshold_class=0.7;
//            trade_threshold = 0;
//            trade_threshold_class = 100;
        }

        //set unseen mail to seen , prevent error trading
        initialEmailConnection();
        int count=0;
        while(true) {
            receiveEmail(cmdFiles);
            try {
                Thread.sleep(1000); //sleep 2 secs
            } catch (InterruptedException e) {
                System.out.println(e + "system interrupt error");
            }
            count++;
            if(count>3600){
                System.out.println(" now time:" +sdff.format(Calendar.getInstance().getTime())+" count restart");
                count=0;
            }
        }

    }

    public static void initial_table(File before_file,File after_file,File index_file) throws IOException {
        //
        System.out.println("run win rate table");
        BufferedReader br_before = new BufferedReader(new FileReader(before_file));
        String line_before =br_before.readLine();
        BufferedReader br_index = new BufferedReader(new FileReader(index_file));
        String line_index =br_index.readLine();
        BufferedReader br_after = new BufferedReader(new FileReader(after_file));
        String line_after =br_after.readLine();
        while(line_index!=null){
            double[] before_array=new double[3];
            int index=0;
            for(String rate:line_before.split("\t")){
                before_array[index++]=Double.parseDouble(rate);
            }
            before_array[index]=1; // let equal win rate=1 wont do anything but get in stage
            before_winrate_table.put(line_index,before_array);

            double[] after_array=new double[9];
            index=0;
            for(String rate:line_after.split("\t")){
                after_array[index++]=Double.parseDouble(rate);
            }
            after_winrate_table.put(line_index,after_array);

            line_before =br_before.readLine();
            line_index =br_index.readLine();
            line_after =br_after.readLine();
        }
    }

    public static void initial_PL_table(File before_file,File after_file) throws IOException {
        //
        System.out.println("run pnl table");
        BufferedReader br_before = new BufferedReader(new FileReader(before_file));
        String line_before =br_before.readLine();
        BufferedReader br_after = new BufferedReader(new FileReader(after_file));
        String line_after =br_after.readLine();
        while(line_before!=null){
            double[] before_array=new double[3];
            int index=0;
            String [] term_before=line_before.split("\t");
            String [] term_after=line_after.split("\t");
            String before_index=term_before[0];
            String after_index=term_after[0];
            for(int i=2;i<term_before.length;i++){
                before_array[index++]=Double.parseDouble(term_before[i]);
            }
            before_array[index]=1; // let equal win rate=1 wont do anything but get in stage
            before_winrate_table.put(before_index,before_array);

            double[] after_array=new double[9];
            index=0;
            for(int i=2;i<term_after.length;i++){
                after_array[index++]=Double.parseDouble(term_after[i]);
            }
            after_winrate_table.put(after_index,after_array);

            line_before =br_before.readLine();
            line_after =br_after.readLine();
        }
    }

    public static void initialEmailConnection() {
        email_props.setProperty(config_props.getProperty("MAILSTORE"), config_props.getProperty("MAILPROTOCOL"));
        session = Session.getInstance(email_props);
        Store store;
        try {
            store = session.getStore();
            host=config_props.getProperty("HOST");
            user=config_props.getProperty("MAILUSER");
            pw=config_props.getProperty("MAILPW");
            try {
                store.connect(host,user,pw );
                Folder inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_WRITE);
                Message messages[] = inbox.search(searchTerm);
                inbox.setFlags(messages, new Flags(Flags.Flag.SEEN), true);
                inbox.close(false);
                store.close();
            } catch (MessagingException e) {
                System.out.println(e+"Message ex");
            }
        } catch (NoSuchProviderException e) {
            System.out.println(e+"NoSuchProviderException ex");
        }
    }


    public static void receiveEmail(HashMap<String,File> cmdFiles){
        try {
            Store store = session.getStore();
            store.connect(host,user,pw);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            Message messages[] = inbox.search(searchTerm);
            if(messages.length>0) {
                System.out.println("Working! There are:" + messages.length + " new messages, now time:" +
                        sdff.format(Calendar.getInstance().getTime()));
            }

            for(Message msg:messages) {
                subject=msg.getSubject();
                if(subject!=null&&subject.contains("{US}")) {
                    String content=getText(msg);
                    if(subject.startsWith("*RELEASE IN ")) { // pre stage start case
                        String cmd="";
                        if(PreventOtherEvent<1) { // if working period have another event, ignore it.
                            //retrieve index from subject
                            Matcher subjectMatcher=subjec_before_tPattern.matcher(subject);
                            if(subjectMatcher.find()){
                                System.out.println(subjectMatcher.group(1)+" hour"); // hour
                                prestage_wait_hour=Integer.parseInt(subjectMatcher.group(1))-1;
                                //      System.out.println(subjectMatcher.group(2)); // event index
                                event=subjectMatcher.group(2);
                                event=event.replaceAll("\\s+","");
                            }
                            System.out.println("prestage event:"+event);
                            //check event with table
                            if(before_winrate_table.containsKey(event)) {
                                String compare_result=parsePre(content);
                                if(compare_result.equals(better)){
                                    pre_index=0;
                                }else if(compare_result.equals(worse)){
                                    pre_index=1;
                                }else{
                                    pre_index=2;
                                }
                                System.out.println("pre_index:"+pre_index +"Win Rate:"+before_winrate_table.get(event)[pre_index]);
                                if(before_winrate_table.get(event)[pre_index]<=trade_threshold) {  // if win rate > 0.5 make order
                                    System.out.println("win rate <"+trade_threshold+", so strategy:"+compare_result+" change to noaction");
                                    compare_result=noaction;
                                }
                                int winrate_class=(before_winrate_table.get(event)[pre_index]>trade_threshold_class)?2:1;
                                cmd += event + "\npre\n";
                                cmd += compare_result;
                                cmd +="\n"+winrate_class;
                                System.out.println("pre stage command:" + cmd);
                                OutputCommand(cmd, cmdFiles);
                                pre_event=event;
                                inprestage = true; //flag for trigger pre stage waiting
                                PreventOtherEvent = 2;
                            }
                        }
                    }else{ //announce stage
                        int winrate_class=1;
                        String cmd="";
                        if(PreventOtherEvent<2) {  //if no pre stage, only announcement
                            pre_index=2; //  spcial case for 10/24
                            System.out.println("test only announce stage");
                        }
                        //else continue pre stage, retrieve index from subject
                        String ann_event="";
                        String compare_result=noaction;//default cmd no action
                        try {
                            Matcher subjectMatcher = subject_announce_Pattern.matcher(subject);
                            if (subjectMatcher.find()) {
                                System.out.println(subjectMatcher.group(1)); // event index
                                ann_event = subjectMatcher.group(1);
                                ann_event = ann_event.replaceAll("\\s+", "");
                            }
                            System.out.println(
                                    "announce event:" + ann_event + "pre_event:'" + pre_event + "' pre ann eq:" +
                                            ann_event.equals(pre_event) +"; or if:"+(ann_event.equals(pre_event) || pre_event.equals("")));
                            //check event with table, and make sure prev_event==announce_event or no pre_event
                            if (ann_event.contains("Surprise") ||
                                    (ann_event.equals(pre_event) || pre_event.equals("")) &&
                                            (after_winrate_table.containsKey(ann_event))) {
                                int index = 5;
                                compare_result = parseActual(content);
                                System.out.println("Announce origin strategy:" + compare_result);
                                switch (pre_index) { //BB	WB	EB	BE	WE	EE	EW	BW	WW
                                    case 0:  //pre better
                                        if (compare_result.equals(better)) {
                                            index = 0;
                                        } else if (compare_result.equals(worse)) {
                                            index = 7;
                                        } else {
                                            index = 3;
                                        }
                                        break;
                                    case 1:  //W
                                        if (compare_result.equals(better)) {
                                            index = 1;
                                        } else if (compare_result.equals(worse)) {
                                            index = 8;
                                        } else {
                                            index = 4;
                                        }
                                        break;
                                    case 2:  //E
                                        if (compare_result.equals(better)) {
                                            index = 2;
                                        } else if (compare_result.equals(worse)) {
                                            index = 6;
                                        } else {
                                            index = 5;
                                        }
                                        break;
                                }
                                if (!ann_event.contains("Surprise") && after_winrate_table.get(ann_event)[index] <=
                                        trade_threshold) {  //if surprise, don't care win rate just do it
                                    System.out.println("win rate <"+trade_threshold+", so origin strategy:" + compare_result +
                                            " change to noaction");
                                    compare_result = noaction;
                                    winrate_class=(after_winrate_table.get(ann_event)[index]>trade_threshold_class)?2:1;
                                }
                                if(!compare_result.equals(noaction)) {  // if no action don't sleep
                                    inAnnouncestage = true; //flag for trigger act stage waiting
                                }
                            }
                        }catch (Exception e){
                            e.printStackTrace();
                            System.out.println("parse announce error, still output default command");
                            compare_result=noaction;
                        }
                        cmd += ann_event + "\nAct\n";
                        cmd += compare_result;
                        cmd +="\n"+winrate_class;
                        System.out.println("Announcement command: " + cmd+"\n*****");
                        OutputCommand(cmd, cmdFiles);
                        PreventOtherEvent=0;
                        pre_event="";
                    }
                }
            }//end for
            inbox.setFlags(messages, new Flags(Flags.Flag.SEEN), true);
            inbox.close(false);
            store.close();
            if(inprestage){
                try{
                    PreStageStrategy(subject,cmdFiles);
                } catch (InterruptedException e) {
                    //e.printStackTrace();
                    System.out.println("InterruptedException ERROR TIME:"+sdff.format(Calendar.getInstance().getTime())+"and do nothing");
                }
            }
            if(inAnnouncestage){
                try {
                    AnnounceStrategy(subject, cmdFiles);
                } catch (InterruptedException e) {
                    //e.printStackTrace();
                    System.out.println("InterruptedException ERROR TIME:"+sdff.format(Calendar.getInstance().getTime())+"and do nothing");
                }


                //announce stage end sleep, clean mailbox
                store.connect(host,user,pw);
                inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_WRITE);
                messages= inbox.search(searchTerm);
                inbox.setFlags(messages, new Flags(Flags.Flag.SEEN), true);
                inbox.close(false);
                store.close();
            }
        } catch (NoSuchProviderException mex) {
            //mex.printStackTrace();
            System.out.println("NoSuchProviderException ERROR TIME:"+sdff.format(Calendar.getInstance().getTime())+"and do nothing");
        } catch (MessagingException e) {
            //e.printStackTrace();
            System.out.println("SSL MessagingException ERROR TIME:"+sdff.format(Calendar.getInstance().getTime())+"and do nothing");
        }  catch (IOException e) {
            //e.printStackTrace();
            System.out.println("IOException ERROR TIME:"+sdff.format(Calendar.getInstance().getTime())+"and do nothing");
        }
    }
    private static void OutputCommand(String command, HashMap<String,File> cmdFiles) {
        for(File file:cmdFiles.values()){
            FileWriter cmdfile_writer;
            try {
                cmdfile_writer = new FileWriter(file);
                cmdfile_writer.write(command);
                cmdfile_writer.close();
            } catch (IOException e) {
                System.out.println("File IO exception");
            }
        }
        System.out.println("output command:"+command);
    }
    private static void AnnounceStrategy(String subject, HashMap<String,File> cmdFiles) throws InterruptedException {
        System.out.println("Sleep 120mins");
        Thread.sleep(2*sixtymin_in_ms);
        String cmd;
        cmd = subject + "\nStage2 close\n";
        cmd += "nothing\n1";
        OutputCommand(cmd,cmdFiles);
        inAnnouncestage=false;
        PreventOtherEvent=0;
        System.out.println("announce stage end");
    }
    private static void PreStageStrategy(String subject,HashMap<String,File> cmdFiles) throws InterruptedException {
        int sleep=prestage_wait_hour*60+30;
        System.out.println("Sleep **"+sleep+"** mins");
        Thread.sleep(prestage_wait_hour * sixtymin_in_ms + thirtymin_in_ms);
        String cmd;
        cmd = subject + "\nCheck\n";
        cmd += "nothing\n1";
        OutputCommand(cmd,cmdFiles);
        System.out.println("Sleep 15mins");
        Thread.sleep(fifteen_in_ms);
        cmd = subject + "\nclose\n";
        cmd += "nothing\n1";
        OutputCommand(cmd,cmdFiles);
        inprestage=false;
        System.out.println("pre stage end");
    }

    // below don't have to read.
    private static String parseActual(String content){
        try {
            ACTUALMatcher = ACTUALPattern.matcher(content);
            PriorMatcher = PriorPattern.matcher( content);
            SURVEYMatcher = SurveyPattern.matcher(content);
            String double_Prior="";
            if(PriorMatcher.find()) {
                double_Prior = PriorMatcher.group(1);
                //System.out.println("Prior:"+double_Prior);
            }
            String double_Actual = "";
            if (ACTUALMatcher.find()) {
                double_Actual = ACTUALMatcher.group(1);
                //System.out.println("ACTUAL:"+double_Actual);
            }
            String double_Survey = "";
            if (SURVEYMatcher.find()) {
                double_Survey = SURVEYMatcher.group(1);
                // System.out.println("SURVEY:"+double_Survey);
            }
            double survey;
            double actual;
            double prior;
            try {
                prior = Double.parseDouble(double_Prior);
                survey = Double.parseDouble(double_Survey);
                actual = Double.parseDouble(double_Actual);
            }catch(NumberFormatException e){
                return noaction;
            }
            System.out.println("Actual:" + actual + "; Survey:" + survey + "; Prior:" + prior);
            if (actual > survey && actual >prior) {
                return better;
            }
            if (actual < survey && actual <prior ) {
                return worse;
            }
        }catch(Exception e){
            System.out.println("parse actual exception");
        }
        return noaction;
    }
    private static String parsePre(String content){
        PriorMatcher = PriorPattern.matcher( content);
        SURVEYMatcher = SurveyPattern.matcher( content);
        String double_Prior="";
        if(PriorMatcher.find()) {
            double_Prior = PriorMatcher.group(1);
            //System.out.println("Prior:"+double_Prior);
        }
        String double_Survey="";
        if(SURVEYMatcher.find()) {
            double_Survey = SURVEYMatcher.group(1);
            //System.out.println("SURVEY:" + double_Survey);
        }
        double survey;
        double prior;
        try {
            survey = Double.parseDouble(double_Survey);
            prior = Double.parseDouble(double_Prior);
        }catch(NumberFormatException e){
            return noaction;
        }
        System.out.println("Prior:"+prior+"; Survey:"+survey);
        if(survey>prior){
            return better;
        }
        if(survey<prior){
            return worse;
        }
        return noaction;
    }
    private static String getText(Part p) throws
            MessagingException, IOException {
        if (p.isMimeType("text/*")) {
            String s = (String)p.getContent();
            p.isMimeType("text/html");
            return s;
        }

        if (p.isMimeType("multipart/alternative")) {
            // prefer html text over plain text
            Multipart mp = (Multipart)p.getContent();
            String text = null;
            for (int i = 0; i < mp.getCount(); i++) {
                Part bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/plain")) {
                    if (text == null)
                        text = getText(bp);
                } else if (bp.isMimeType("text/html")) {
                    String s = getText(bp);
                    if (s != null)
                        return s;
                } else {
                    return getText(bp);
                }
            }
            return text;
        } else if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart)p.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                String s = getText(mp.getBodyPart(i));
                if (s != null)
                    return s;
            }
        }

        return null;
    }
}