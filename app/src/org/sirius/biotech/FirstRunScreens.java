package org.sirius.biotech;
import android.view.*;
import android.widget.*;

/** Three original onboarding illustrations; agreement count is two, not three. */
final class FirstRunScreens {
 static View consent(MainActivity a){
  boolean ru="ru".equals(a.getResources().getConfiguration().getLocales().get(0).getLanguage());
  LinearLayout root=a.column();root.setBackgroundColor(a.bg);LinearLayout card=a.column();card.setBackground(a.shape(0xffffffff,8));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-1);cp.setMargins(a.dp(10),a.dp(10),a.dp(10),0);root.addView(card,cp);
  final int[] page={0};ImageView image=new ImageView(a);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(a.dp(120),a.dp(120));ip.gravity=Gravity.CENTER;ip.topMargin=a.dp(90);card.addView(image,ip);
  TextView title=a.text("",22,a.ink),description=a.text("",16,a.muted),dots=a.text("",18,a.muted);title.setGravity(Gravity.CENTER);description.setGravity(Gravity.CENTER);dots.setGravity(Gravity.CENTER);card.addView(title);card.addView(description);View space=new View(a);card.addView(space,new LinearLayout.LayoutParams(-1,0,1));card.addView(dots);
  String[][] titles={{"Общайтесь с участниками","Будьте активны","Следите за новостями"},{"Connect with participants","Be active","Follow the news"}};
  String[][] texts={{"Находите партнёров и коллег, общайтесь и обменивайтесь контактами.","Делитесь впечатлениями, задавайте вопросы выступающим и участвуйте в обсуждениях.","Будьте в курсе новостей мероприятия и объявлений организаторов."},{"Find partners and colleagues, talk and exchange contacts.","Share impressions, ask speakers questions and join discussions.","Follow event news and organizer announcements."}};
  Runnable update=()->{image.setImageResource(a.getResources().getIdentifier("privacy_policy_page_"+(page[0]+1),"drawable",a.getPackageName()));title.setText(titles[ru?0:1][page[0]]);description.setText(texts[ru?0:1][page[0]]);dots.setText(page[0]==0?"● ○ ○":page[0]==1?"○ ● ○":"○ ○ ●");};update.run();dots.setOnClickListener(v->{page[0]=(page[0]+1)%3;update.run();});image.setOnClickListener(v->{page[0]=(page[0]+1)%3;update.run();});
  CheckBox terms=new CheckBox(a),personal=new CheckBox(a);terms.setText(ru?"Я принимаю условия использования и политику конфиденциальности":"I accept the Terms of Use and Privacy Policy");personal.setText(ru?"Я даю согласие на обработку персональных данных":"I consent to processing my personal data");card.addView(terms);card.addView(personal);
  a.button(card,ru?"Условия и конфиденциальность":"Terms and Privacy",()->a.dialog(ru?"Условия и конфиденциальность":"Terms and Privacy",a.dataUseText()));
  a.button(card,ru?"Продолжить":"Continue",()->{if(!terms.isChecked()||!personal.isChecked()){a.toast(ru?"Подтвердите оба согласия":"Accept both agreements");return;}a.store.edit().putBoolean("consents-v1",true).apply();a.show("welcome");});return root;
 }
 static View registration(MainActivity a){try{return a.originalScreens().registration();}catch(Exception | LinkageError e){android.util.Log.e("SiriusOriginalUI","Registration renderer unavailable",e);}boolean ru="ru".equals(a.getResources().getConfiguration().getLocales().get(0).getLanguage());LinearLayout root=a.column();root.setBackgroundColor(a.bg);LinearLayout card=a.column();card.setBackground(a.shape(0xffffffff,8));root.addView(card,new LinearLayout.LayoutParams(-1,-1));a.originalImage(card,"image_auth_start_120",120);TextView title=a.bold(ru?"Регистрация":"Registration",22);title.setGravity(Gravity.CENTER);card.addView(title);TextView text=a.text(ru?"Мы высылаем приглашение с регистрационным ID по e-mail и СМС":"We send registration ID via SMS and e-mail",16,a.muted);text.setGravity(Gravity.CENTER);card.addView(text);card.addView(new View(a),new LinearLayout.LayoutParams(-1,0,1));a.button(card,ru?"Войти":"Sign in",()->a.show("login"));return root;}
}
