package loglens;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Local-only logcat viewer: the native module loads this class into LeviLauncher. */
public final class LogLens {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayBlockingQueue<String> incoming = new ArrayBlockingQueue<String>(4096);
    private static final AtomicInteger dropped = new AtomicInteger();
    private static final ArrayList<Entry> entries = new ArrayList<Entry>();
    private static final HashSet<Long> selected = new HashSet<Long>();
    private static final int MAX_LINES = 2000;
    private static volatile boolean enabled;
    private static volatile Process logProcess;
    private static volatile Thread reader;
    private static Activity activity;
    private static FrameLayout root;
    private static Console console;
    private static View bubble;
    private static long nextId;
    private static boolean paused;
    private static String selectedText = "";
    private static final ArrayList<String> snippets = new ArrayList<String>();
    private static String readerStatus = "Starting logcat...";
    private static final Runnable monitor = new Runnable() {
        public void run() {
            if (!enabled) return;
            try {
                Class<?> manager = Class.forName("org.levimc.launcher.core.mods.inbuilt.overlay.InbuiltOverlayManager");
                Object instance = manager.getMethod("getInstance").invoke(null);
                if (instance != null) {
                    Field field = manager.getDeclaredField("activity"); field.setAccessible(true);
                    Activity found = (Activity)field.get(instance);
                    if (found != null && !found.isFinishing() && found != activity) attach(found);
                }
            } catch (Throwable ignored) { }
            MAIN.postDelayed(this, 1000);
        }
    };
    private static final Runnable drain = new Runnable() {
        public void run() {
            if (!enabled) return;
            if (paused) {
                if (console != null && console.getVisibility() == View.VISIBLE) console.updateStatus();
                MAIN.postDelayed(this, 200);
                return;
            }
            boolean changed = false;
            for (int i = 0; i < 400; i++) {
                String line = incoming.poll(); if (line == null) break;
                if (entries.size() >= MAX_LINES) selected.remove(entries.remove(0).id);
                entries.add(new Entry(++nextId, line)); changed = true;
            }
            if (console != null && console.getVisibility() == View.VISIBLE) {
                if (changed && !paused) console.refresh(true);
                else console.updateStatus();
            }
            MAIN.postDelayed(this, 200);
        }
    };
    public static void start() { MAIN.post(new Runnable(){public void run(){
        if (enabled) return; enabled=true; paused=false; startReader(); monitor.run(); drain.run();
    }}); }
    public static void stop() { MAIN.post(new Runnable(){public void run(){
        enabled=false; MAIN.removeCallbacks(monitor); MAIN.removeCallbacks(drain);
        Process p=logProcess; logProcess=null; if(p!=null)p.destroy();
        Thread t=reader; reader=null; if(t!=null)t.interrupt();
        incoming.clear(); entries.clear(); selected.clear(); selectedText=""; snippets.clear(); dropped.set(0); detach();
    }}); }
    private static void startReader() {
        reader = new Thread(new Runnable(){public void run(){
            try {
                ArrayList<String> args = new ArrayList<String>();
                args.add("logcat"); args.add("-v"); args.add("threadtime");
                args.add("--pid"); args.add(Integer.toString(android.os.Process.myPid()));
                Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
                if (!enabled) { p.destroy(); return; }
                logProcess=p; status("Reading this process (PID " + android.os.Process.myPid() + ")");
                BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(),"UTF-8"));
                String line;
                while(enabled && (line=r.readLine())!=null){
                    if(line.length()>4096) line=line.substring(0,4096)+" … [line truncated]";
                    if(!incoming.offer(line)) dropped.incrementAndGet();
                }
                r.close(); if(enabled) status("Logcat stopped. Check device restrictions or restart the mod.");
            } catch(Exception e) { if(enabled) status("Logcat unavailable: " + e.getClass().getSimpleName()); }
        }},"LogLens-logcat"); reader.setDaemon(true);reader.start();
    }
    private static void status(final String value){ MAIN.post(new Runnable(){public void run(){readerStatus=value;if(console!=null)console.updateStatus();}}); }
    private static int dp(Context c,float n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
    private static GradientDrawable background(int color,float radius,Context c){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    private static void detach(){
        if(root!=null&&root.getParent() instanceof ViewGroup)((ViewGroup)root.getParent()).removeView(root);
        root=null;console=null;bubble=null;activity=null;
    }
    private static void attach(Activity a){
        detach();activity=a;root=new FrameLayout(a);root.setClipChildren(false);
        console=new Console(a);console.setVisibility(View.GONE);root.addView(console,new FrameLayout.LayoutParams(-1,-1));
        TextView b=new TextView(a);bubble=b;b.setText("LOG");b.setTextSize(12);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setGravity(Gravity.CENTER);b.setTextColor(Color.WHITE);b.setBackground(background(0xee533ad2,14,a));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(a,50),dp(a,50),Gravity.RIGHT|Gravity.CENTER_VERTICAL);p.rightMargin=dp(a,12);root.addView(b,p);
        final FrameLayout host=root;final View button=bubble;final Console panel=console;final SharedPreferences pref=a.getSharedPreferences("loglens_ui",0);
        b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){panel.open();}});
        b.setOnTouchListener(new View.OnTouchListener(){float ox,oy,x,y;boolean moved;
            public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN: ox=e.getRawX();oy=e.getRawY();x=v.getX();y=v.getY();moved=false;return true;
                case MotionEvent.ACTION_MOVE:float dx=e.getRawX()-ox,dy=e.getRawY()-oy;if(!moved&&dx*dx+dy*dy>dp(v.getContext(),8)*dp(v.getContext(),8))moved=true;
                    if(moved){v.setX(Math.max(0,Math.min(host.getWidth()-v.getWidth(),x+dx)));v.setY(Math.max(0,Math.min(host.getHeight()-v.getHeight(),y+dy)));}return true;
                case MotionEvent.ACTION_UP:if(moved){pref.edit().putFloat("x",v.getX()/Math.max(1,host.getWidth()-v.getWidth())).putFloat("y",v.getY()/Math.max(1,host.getHeight()-v.getHeight())).apply();}else v.performClick();return true;
                case MotionEvent.ACTION_CANCEL:return true;default:return true;}}
        });
        a.addContentView(root,new ViewGroup.LayoutParams(-1,-1));
        host.post(new Runnable(){public void run(){if(pref.contains("x")){button.setX(Math.max(0,Math.min(1,pref.getFloat("x",1)))*Math.max(0,host.getWidth()-button.getWidth()));button.setY(Math.max(0,Math.min(1,pref.getFloat("y",.5f)))*Math.max(0,host.getHeight()-button.getHeight()));}}});
    }
    private static final class Entry {
        final long id; final String line; final char level; final String tag;
        private static final Pattern P=Pattern.compile("^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+([^:]+):");
        Entry(long id,String line){this.id=id;this.line=line;Matcher m=P.matcher(line);if(m.find()){level=m.group(1).charAt(0);tag=m.group(2).trim();}else{level='?';tag="";}}
    }
    private static final class Console extends FrameLayout {
        EditText search,exclude,tag;CheckBox regex,matchCase,auto;Spinner levels;
        TextView status;Button pause;ListView list;LogAdapter adapter;Pattern includePattern;String error="";
        final ArrayList<Entry> filtered=new ArrayList<Entry>();long lastShown;
        Console(Context c){super(c);setBackgroundColor(0x88000000);setClickable(true);
            LinearLayout card=new LinearLayout(c);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(c,8),dp(c,5),dp(c,8),dp(c,5));card.setBackground(background(0xf21c1d1f,12,c));
            FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,-1);cp.setMargins(dp(c,6),dp(c,5),dp(c,6),dp(c,5));addView(card,cp);
            LinearLayout title=row(c);TextView name=label(c,"Logcat: live",14,0xffffffff);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.addView(name,new LinearLayout.LayoutParams(0,dp(c,40),1));
            Button more=btn(c,"⋮"),saveButton=btn(c,"Save"),close=btn(c,"×"),clear=btn(c,"Clear");pause=btn(c,paused?"▶":"Ⅱ");
            title.addView(more);title.addView(saveButton);title.addView(pause);title.addView(clear);title.addView(close);card.addView(title);
            LinearLayout filters=row(c);levels=new Spinner(c);String[] opts={"ALL","V+","D+","I+","W+","E+","F"};
            ArrayAdapter<String> levelAdapter=new ArrayAdapter<String>(c,android.R.layout.simple_spinner_item,opts){public View getView(int pos,View old,ViewGroup parent){TextView t=(TextView)super.getView(pos,old,parent);t.setTextColor(Color.WHITE);t.setTextSize(12);return t;}};
            levelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);levels.setAdapter(levelAdapter);levels.setBackground(background(0xff303132,6,c));filters.addView(levels,new LinearLayout.LayoutParams(dp(c,68),dp(c,38)));
            search=edit(c,"Keyword filter",145);exclude=edit(c,"Blacklist (comma-separated)",145);tag=edit(c,"Tag contains",130);
            LinearLayout.LayoutParams qlp=new LinearLayout.LayoutParams(0,dp(c,38),1);qlp.leftMargin=dp(c,5);filters.addView(search,qlp);
            LinearLayout.LayoutParams elp=new LinearLayout.LayoutParams(0,dp(c,38),1);elp.leftMargin=dp(c,5);filters.addView(exclude,elp);card.addView(filters);
            final LinearLayout advanced=row(c);regex=check(c,"Regex",false);matchCase=check(c,"Case",false);auto=check(c,"Auto-scroll",true);advanced.addView(tag);advanced.addView(regex);advanced.addView(matchCase);advanced.addView(auto);final HorizontalScrollView extra=hscroll(c,advanced);extra.setVisibility(View.GONE);card.addView(extra);
            status=label(c,"",10,0xffa7a9ad);card.addView(status,new LinearLayout.LayoutParams(-1,dp(c,23)));
            list=new ListView(c);list.setDividerHeight(0);list.setBackgroundColor(Color.TRANSPARENT);adapter=new LogAdapter(c,this);list.setAdapter(adapter);card.addView(list,new LinearLayout.LayoutParams(-1,0,1));
            TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int n,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){refresh(false);}public void afterTextChanged(Editable e){}};
            search.addTextChangedListener(watcher);exclude.addTextChangedListener(watcher);tag.addTextChangedListener(watcher);
            CompoundButton.OnCheckedChangeListener checks=new CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(CompoundButton b,boolean checked){refresh(false);}};regex.setOnCheckedChangeListener(checks);matchCase.setOnCheckedChangeListener(checks);
            levels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> parent,View v,int pos,long id){refresh(false);}public void onNothingSelected(AdapterView<?> p){}});
            more.setOnClickListener(new View.OnClickListener(){public void onClick(View v){extra.setVisibility(extra.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);}});
            pause.setOnClickListener(new View.OnClickListener(){public void onClick(View v){setPaused(!paused);}});
            clear.setOnClickListener(new View.OnClickListener(){public void onClick(View v){entries.clear();incoming.clear();selected.clear();selectedText="";snippets.clear();dropped.set(0);refresh(false);}});
            close.setOnClickListener(new View.OnClickListener(){public void onClick(View v){close();}});
            saveButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){showSaveMenu();}});
        }
        void showSaveMenu(){
            String[] choices={"Save selected text", "Add selected text to collection", "Save selected lines", "Save filtered logs", "Select all filtered lines", "Clear selections"};
            new AlertDialog.Builder(getContext()).setTitle("Save to a text file").setItems(choices,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){
                if(which==0){StringBuilder s=new StringBuilder();for(String piece:snippets){if(s.length()>0)s.append('\n');s.append(piece);}if(!selectedText.isEmpty()){if(s.length()>0)s.append('\n');s.append(selectedText);}save(s.toString(),"text");}
                else if(which==1){if(selectedText.isEmpty())Toast.makeText(getContext(),"Long-press a line and select text first.",Toast.LENGTH_LONG).show();else{snippets.add(selectedText);selectedText="";updateStatus();Toast.makeText(getContext(),"Text added to collection",Toast.LENGTH_SHORT).show();}}
                else if(which==2){StringBuilder s=new StringBuilder();for(Entry e:entries)if(selected.contains(e.id))s.append(e.line).append('\n');save(s.toString(),"selected");}
                else if(which==3){StringBuilder s=new StringBuilder();for(Entry e:filtered)s.append(e.line).append('\n');save(s.toString(),"filtered");}
                else if(which==4){for(Entry e:filtered)selected.add(e.id);adapter.notifyDataSetChanged();updateStatus();}
                else {selected.clear();selectedText="";snippets.clear();adapter.notifyDataSetChanged();updateStatus();}
            }}).setNegativeButton("Cancel",null).show();
        }
        void open(){if(bubble!=null)bubble.setVisibility(View.GONE);setVisibility(View.VISIBLE);setAlpha(0f);animate().alpha(1f).setDuration(180).start();refresh(true);}
        void close(){InputMethodManager imm=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);if(imm!=null)imm.hideSoftInputFromWindow(getWindowToken(),0);setVisibility(View.GONE);if(bubble!=null)bubble.setVisibility(View.VISIBLE);}
        void setPaused(boolean value){paused=value;pause.setText(paused?"▶":"Ⅱ");if(!paused)refresh(true);else updateStatus();}
        void refresh(boolean scroll){
            if(search==null||adapter==null)return;
            error="";includePattern=null;String q=search.getText().toString();
            if(regex.isChecked()&&!q.isEmpty())try{includePattern=Pattern.compile(q,matchCase.isChecked()?0:Pattern.CASE_INSENSITIVE);}catch(PatternSyntaxException e){error="Invalid search regex";}
            filtered.clear();if(error.isEmpty())for(Entry e:entries)if(passes(e))filtered.add(e);
            adapter.notifyDataSetChanged();updateStatus();
            if(scroll&&auto.isChecked()&&!filtered.isEmpty())list.setSelection(filtered.size()-1);
        }
        boolean contains(String s,String q){return matchCase.isChecked()?s.contains(q):s.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));}
        boolean passes(Entry e){
            if(!error.isEmpty())return false;String query=search.getText().toString();
            if(!query.isEmpty()&&(regex.isChecked()?(includePattern==null||!includePattern.matcher(e.line).find()):!contains(e.line,query)))return false;
            String ex=exclude.getText().toString();for(String word:ex.split(",")){word=word.trim();if(!word.isEmpty()&&contains(e.line,word))return false;}
            String t=tag.getText().toString();if(!t.isEmpty()&&!contains(e.tag,t))return false;
            int min=levels.getSelectedItemPosition();if(min>0){int rank="VDIWEF".indexOf(e.level);if(rank<min-1)return false;}
            return true;
        }
        void updateStatus(){if(status==null)return;status.setText((error.isEmpty()?readerStatus:error)+"  ·  "+filtered.size()+" lines  ·  "+selected.size()+" selected  ·  "+(snippets.size()+(selectedText.isEmpty()?0:1))+" text"+(dropped.get()>0?"  ·  dropped "+dropped.get():"")+(paused?"  ·  PAUSED":""));}
        void save(String content,String kind){if(content==null||content.isEmpty()){Toast.makeText(getContext(),kind.equals("text")?"Pause, then long-press a line and select text first.":"No logs selected or matching.",Toast.LENGTH_LONG).show();return;}
            final Context app=getContext().getApplicationContext();final String data=content;final String filename="LogLens_"+kind+"_"+new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS",Locale.US).format(new Date())+".txt";
            new Thread(new Runnable(){public void run(){Uri uri=null;try{
                if(Build.VERSION.SDK_INT>=29){ContentValues values=new ContentValues();values.put("_display_name",filename);values.put("mime_type","text/plain");values.put("relative_path",Environment.DIRECTORY_DOWNLOADS+"/");values.put("is_pending",1);
                    uri=app.getContentResolver().insert(Uri.parse("content://media/external_primary/downloads"),values);if(uri==null)throw new Exception("Downloads entry unavailable");
                    OutputStream out=app.getContentResolver().openOutputStream(uri);if(out==null)throw new Exception("Downloads stream unavailable");try{out.write(data.getBytes("UTF-8"));}finally{out.close();}
                    ContentValues done=new ContentValues();done.put("is_pending",0);if(app.getContentResolver().update(uri,done,null,null)!=1)throw new Exception("Could not finish export");
                }else{File dir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);if(!dir.isDirectory()&&!dir.mkdirs())throw new Exception("Downloads folder unavailable");File f=new File(dir,filename);FileOutputStream out=new FileOutputStream(f);try{out.write(data.getBytes("UTF-8"));}finally{out.close();}}
                toast(app,"Saved to Downloads: "+filename);
            }catch(Exception e){if(uri!=null)try{app.getContentResolver().delete(uri,null,null);}catch(Exception ignored){}toast(app,Build.VERSION.SDK_INT<29?"Save failed: Android 9 may block Downloads access for the launcher.":"Save failed: "+e.getClass().getSimpleName());}
            }},"LogLens-export").start();
        }
    }
    private static void toast(final Context c,final String s){MAIN.post(new Runnable(){public void run(){Toast.makeText(c,s,Toast.LENGTH_LONG).show();}});}
    private static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private static HorizontalScrollView hscroll(Context c,View child){HorizontalScrollView h=new HorizontalScrollView(c);h.setHorizontalScrollBarEnabled(false);h.addView(child);return h;}
    private static TextView label(Context c,String text,int size,int color){TextView v=new TextView(c);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setGravity(Gravity.CENTER_VERTICAL);v.setSingleLine(true);return v;}
    private static Button btn(Context c,String text){Button b=new Button(c);b.setAllCaps(false);b.setText(text);b.setTextSize(11);b.setTextColor(Color.WHITE);b.setPadding(dp(c,10),0,dp(c,10),0);b.setBackground(background(0xff35354b,7,c));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(c,38));p.rightMargin=dp(c,5);b.setLayoutParams(p);return b;}
    private static CheckBox check(Context c,String text,boolean value){CheckBox v=new CheckBox(c);v.setText(text);v.setTextSize(11);v.setTextColor(Color.WHITE);v.setChecked(value);return v;}
    private static EditText edit(Context c,String hint,int width){EditText v=new EditText(c);v.setSingleLine(true);v.setTextSize(12);v.setTextColor(Color.WHITE);v.setHintTextColor(0xff9a9fb0);v.setHint(hint);v.setPadding(dp(c,7),0,dp(c,7),0);v.setBackground(background(0xff252638,6,c));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(c,width),dp(c,38));p.rightMargin=dp(c,5);v.setLayoutParams(p);return v;}
    private static int severityColor(char level){switch(level){case 'V':return 0xffb0b0b0;case 'D':return 0xff3de3ed;case 'I':return 0xff39e14e;case 'W':return 0xffffdf47;case 'E':return 0xffff5b66;case 'F':return 0xffff3d94;default:return 0xffdedede;}}
    private static CharSequence colorize(Entry entry){
        SpannableStringBuilder result=new SpannableStringBuilder(entry.line);int n=result.length(),color=severityColor(entry.level);
        if(n>0)result.setSpan(new ForegroundColorSpan(color),0,n,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Matcher m=Entry.P.matcher(entry.line);if(m.find()){
            result.setSpan(new ForegroundColorSpan(0xffacacac),0,Math.min(18,n),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            result.setSpan(new BackgroundColorSpan(color),m.start(1),m.end(1),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            result.setSpan(new ForegroundColorSpan(0xff111111),m.start(1),m.end(1),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            result.setSpan(new ForegroundColorSpan(0xfff4f4f4),m.start(2),m.end(2),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            result.setSpan(new StyleSpan(Typeface.BOLD),m.start(2),m.end(2),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return result;
    }
    private static final class LogAdapter extends BaseAdapter {
        final Context context;final Console panel;LogAdapter(Context c,Console p){context=c;panel=p;}
        public int getCount(){return panel.filtered.size();} public Object getItem(int p){return panel.filtered.get(p);} public long getItemId(int p){return panel.filtered.get(p).id;} public boolean hasStableIds(){return true;}
        public View getView(int position,View recycled,ViewGroup parent){
            SelectableText text;if(recycled==null){text=new SelectableText(context,panel);text.setTextSize(11);text.setTypeface(Typeface.MONOSPACE);text.setTextIsSelectable(true);text.setPadding(dp(context,4),dp(context,2),dp(context,4),dp(context,2));}else text=(SelectableText)recycled;
            text.bind(panel.filtered.get(position));return text;
        }
    }
    private static final class SelectableText extends TextView {
        Console panel;Entry entry;boolean binding;
        SelectableText(Context c,Console p){super(c);panel=p;
            setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(entry==null)return;if(selected.contains(entry.id))selected.remove(entry.id);else selected.add(entry.id);setBackgroundColor(selected.contains(entry.id)?0x665b7b9b:Color.TRANSPARENT);panel.updateStatus();}});
            setOnLongClickListener(new View.OnLongClickListener(){public boolean onLongClick(View v){if(!paused)panel.setPaused(true);return false;}});
        }
        void bind(Entry e){if(entry==null||entry.id!=e.id){binding=true;entry=e;setText(colorize(e));binding=false;}setBackgroundColor(selected.contains(e.id)?0x665b7b9b:Color.TRANSPARENT);}
        protected void onSelectionChanged(int start,int end){super.onSelectionChanged(start,end);if(!binding&&start>=0&&end>start&&end<=getText().length()){selectedText=getText().subSequence(start,end).toString();if(panel!=null)panel.updateStatus();}}
    }

}
