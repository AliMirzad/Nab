package ir.nab;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.Bidi;
import java.awt.Color;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class ReportFiles {
    private static final String NOTICE="مبنای بازه: شروع برنامه‌ریزی‌شده گشت، به وقت تهران. دریافت‌نشده لزوماً به معنی انجام‌نشدن نیست؛ ممکن است گوشی آفلاین باشد.";

    public byte[] xlsx(ReportService.Report report) throws IOException {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var normal=book.createCellStyle();normal.setAlignment(HorizontalAlignment.RIGHT);normal.setVerticalAlignment(VerticalAlignment.TOP);normal.setWrapText(true);
            var font=book.createFont();font.setFontName("Vazirmatn");font.setFontHeightInPoints((short)11);normal.setFont(font);
            var header=book.createCellStyle();header.cloneStyleFrom(normal);header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var white=book.createFont();white.setFontName("Vazirmatn");white.setBold(true);white.setColor(IndexedColors.WHITE.getIndex());header.setFont(white);
            var date=book.createCellStyle();date.cloneStyleFrom(normal);date.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            var integer=book.createCellStyle();integer.cloneStyleFrom(normal);integer.setDataFormat(book.createDataFormat().getFormat("#,##0"));
            var summary=sheet(book,"خلاصه",new int[]{30,32,20,20,20,20,20,20,20});int n=0;
            line(summary,n++,header,"گزارش گشت ناب","مقدار");
            line(summary,n++,normal,"زمان تهیه (شمسی / تهران)",ReportService.persian(report.generatedAt(),true));
            for(var c:report.criteria().entrySet())line(summary,n++,normal,c.getKey(),c.getValue());
            line(summary,n++,normal,"راهنما",NOTICE);summary.getRow(n-1).setHeightInPoints(62);
            line(summary,n++,normal,"شناسه گزارش",report.id());
            n++;var t=report.summary();
            line(summary,n++,header,"کل نوبت‌ها","تکمیل‌شده","در حال گشت","متوقف‌شده","دریافت‌نشده","در انتظار شروع","بازدید مورد انتظار","بازدید ثبت‌شده","رخداد");
            line(summary,n++,integer,t.total(),t.finished(),t.active(),t.aborted(),t.overdue(),t.pending(),t.expectedStops(),t.visitedStops(),t.incidents());
            n++;line(summary,n++,header,"نگهبان","کل نوبت‌ها","تکمیل‌شده","در حال گشت","متوقف‌شده","دریافت‌نشده","در انتظار شروع","بازدید ثبت‌شده","رخداد");
            for(var g:report.guards()){var v=g.totals();line(summary,n++,normal,g.guardName(),v.total(),v.finished(),v.active(),v.aborted(),v.overdue(),v.pending(),v.visitedStops(),v.incidents());}
            summary.createFreezePane(1,1);
            var detail=sheet(book,"نوبت‌های گشت",new int[]{8,24,26,30,32,24,24,24,24,24,24,24,24,22,16,16,14,18,18,28,40,40});
            line(detail,0,header,"ردیف","نگهبان","محل","مسیر","نوبت گشت","شروع برنامه (شمسی)","پایان برنامه (شمسی)","شروع واقعی (شمسی)","پایان واقعی (شمسی)","شروع برنامه (میلادی)","پایان برنامه (میلادی)","شروع واقعی (میلادی)","پایان واقعی (میلادی)","وضعیت","تعداد نقاط","بازدید ثبت‌شده","رخداد","تأخیر شروع (دقیقه)","مدت (دقیقه)","بررسی ساعت دستگاه","شناسه نوبت","شناسه گشت");
            n=1;
            for(var r:report.rows()) {
                line(detail,n,normal,n,r.guardName(),r.siteName(),r.routeName(),r.label(),ReportService.persian(r.scheduledStart(),true),ReportService.persian(r.scheduledEnd(),true),ReportService.persian(r.actualStart(),true),ReportService.persian(r.actualEnd(),true),local(r.scheduledStart()),local(r.scheduledEnd()),local(r.actualStart()),local(r.actualEnd()),ReportService.statusLabel(r.status()),r.expectedStops(),r.visitedStops(),r.incidents(),r.delayMinutes(),r.durationMinutes(),r.clockInvalid()?"ساعت پایان قبل از شروع است":"",r.assignmentId(),r.tourId());
                for(int col=9;col<=12;col++)detail.getRow(n).getCell(col).setCellStyle(date);
                for(int col=14;col<=18;col++)detail.getRow(n).getCell(col).setCellStyle(integer);
                detail.getRow(n++).setHeightInPoints(38);
            }
            detail.createFreezePane(2,1);detail.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,n-1),0,21));
            if(report.includeEvents()) {
                var events=sheet(book,"رویدادها",new int[]{24,28,30,10,24,28,24,24,24,34,18,70,40});
                line(events,0,header,"نگهبان","مسیر","نوبت گشت","ترتیب","نوع","نقطه","زمان ثبت (شمسی)","زمان دریافت (شمسی)","زمان ثبت (میلادی)","موقعیت GPS / دقت متر","شدت","توضیحات","شناسه رویداد");int i=1;
                for(var r:report.rows())for(var e:r.events()){
                    line(events,i,normal,r.guardName(),r.routeName(),r.label(),e.sequence(),ReportService.eventLabel(e.type()),e.checkpoint(),ReportService.persian(e.capturedAt(),true),ReportService.persian(e.receivedAt(),true),local(e.capturedAt()),e.location(),e.severity(),e.note(),e.id());events.getRow(i).getCell(8).setCellStyle(date);events.getRow(i++).setHeightInPoints(60);
                }
                events.createFreezePane(1,1);events.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,i-1),0,12));
            }
            book.write(out);return out.toByteArray();
        }
    }
    private static LocalDateTime local(String iso){return iso==null?null:Instant.parse(iso).atZone(ReportService.ZONE).toLocalDateTime();}
    private static Sheet sheet(XSSFWorkbook b,String name,int[] widths){var s=b.createSheet(name);s.setRightToLeft(true);s.setDisplayGridlines(false);for(int i=0;i<widths.length;i++)s.setColumnWidth(i,widths[i]*256);return s;}
    private static void line(Sheet sheet,int index,CellStyle style,Object... values){var r=sheet.createRow(index);r.setHeightInPoints(30);for(int i=0;i<values.length;i++){var c=r.createCell(i);c.setCellStyle(style);var v=values[i];if(v instanceof Number num)c.setCellValue(num.doubleValue());else if(v instanceof LocalDateTime time)c.setCellValue(time);else if(v!=null)c.setCellValue(v.toString());}}

    public byte[] pdf(ReportService.Report report) throws IOException {
        try(var document=new PDDocument();var out=new ByteArrayOutputStream();var input=ReportFiles.class.getResourceAsStream("/fonts/Vazirmatn-Regular.ttf")){
            var font=PDType0Font.load(document,Objects.requireNonNull(input),true);
            document.getDocumentInformation().setTitle("Nab patrol report");document.getDocumentInformation().setSubject(report.id());
            try(var p=new Pdf(document,font,report)) {
                p.heading("فیلترهای انتخاب‌شده",11);
                p.paragraph(String.join(" | ",report.criteria().entrySet().stream().map(e->e.getKey()+": "+e.getValue()).toList()),9);
                p.heading("خلاصه گزارش",13);var t=report.summary();
                p.table(new String[]{"کل نوبت‌ها","تکمیل‌شده","در حال گشت","متوقف‌شده","دریافت‌نشده","در انتظار شروع","بازدید ثبت‌شده","رخداد"},new float[]{95,95,95,95,95,95,105,95},List.<String[]>of(new String[]{s(t.total()),s(t.finished()),s(t.active()),s(t.aborted()),s(t.overdue()),s(t.pending()),s(t.visitedStops()),s(t.incidents())}));
                p.paragraph(NOTICE,9);p.heading("جمع‌بندی به تفکیک نگهبان",12);
                List<String[]> guards=new ArrayList<>();for(var g:report.guards()){var v=g.totals();guards.add(new String[]{g.guardName(),s(v.total()),s(v.finished()),s(v.active()),s(v.aborted()),s(v.overdue()),s(v.pending()),s(v.incidents())});}
                p.table(new String[]{"نگهبان","کل نوبت‌ها","تکمیل‌شده","در حال گشت","متوقف‌شده","دریافت‌نشده","در انتظار شروع","رخداد"},new float[]{180,85,85,85,85,85,85,80},guards);
                p.heading("جزئیات نوبت‌های گشت",12);
                List<String[]> rows=new ArrayList<>();for(var r:report.rows())rows.add(new String[]{r.guardName(),r.label(),r.routeName()+" / "+r.siteName(),ReportService.persian(r.scheduledStart(),true)+"\n"+ReportService.persian(r.scheduledEnd(),true),ReportService.persian(r.actualStart(),true)+"\n"+ReportService.persian(r.actualEnd(),true),ReportService.statusLabel(r.status()),r.visitedStops()+" / "+r.expectedStops(),r.delayMinutes()==null?"-":s(r.delayMinutes()),r.clockInvalid()?"ساعت نامعتبر":r.durationMinutes()==null?"-":s(r.durationMinutes())});
                p.table(new String[]{"نگهبان","نوبت گشت","مسیر / محل","شروع / پایان برنامه","شروع / پایان واقعی","وضعیت","بازدید / کل","تأخیر دقیقه","مدت دقیقه"},new float[]{95,120,125,98,98,85,55,45,49},rows);
                if(report.includeEvents())for(var r:report.rows())if(!r.events().isEmpty()){
                    p.heading("رویدادها: "+r.guardName()+" — "+r.label(),11);
                    List<String[]> events=new ArrayList<>();for(var e:r.events())events.add(new String[]{s(e.sequence()),ReportService.eventLabel(e.type()),e.checkpoint(),ReportService.persian(e.capturedAt(),true),e.location(),e.severity()+" "+e.note()});
                    p.table(new String[]{"ترتیب","نوع","نقطه","زمان ثبت","موقعیت / دقت متر","توضیحات / شدت"},new float[]{40,85,110,110,145,280},events);
                }
                if(report.rows().isEmpty())p.paragraph("برای فیلترهای انتخاب‌شده نوبت گشتی یافت نشد.",11);
            }
            document.save(out);return out.toByteArray();
        }
    }
    private static String s(Number n){return n.toString();}
    private static class Pdf implements AutoCloseable {
        final PDDocument doc;final PDType0Font font;final ReportService.Report report;
        final float width=PDRectangle.A4.getHeight(),height=PDRectangle.A4.getWidth(),margin=28;
        PDPageContentStream stream;float y;int page=0;
        Pdf(PDDocument d,PDType0Font f,ReportService.Report r)throws IOException{doc=d;font=f;report=r;newPage();}
        private String visual(String logical) throws IOException {
            // Remove controls and unsupported glyphs from untrusted names/notes without changing RTL order.
            String clean=logical.replaceAll("[\\p{Cc}\\p{Cf}]"," ");
            try{var shaped=new ArabicShaping(ArabicShaping.LETTERS_SHAPE).shape(clean);var bidi=new Bidi();bidi.setPara(shaped,clean.matches(".*[\\p{IsArabic}].*")?Bidi.LEVEL_DEFAULT_RTL:Bidi.LEVEL_DEFAULT_LTR,null);String result=bidi.writeReordered(Bidi.DO_MIRRORING|Bidi.REMOVE_BIDI_CONTROLS);var safe=new StringBuilder();result.codePoints().forEach(cp->{try{font.getStringWidth(new String(Character.toChars(cp)));safe.appendCodePoint(cp);}catch(Exception ex){safe.append('?');}});return safe.toString();}catch(Exception e){throw new IOException("Cannot shape report text",e);}
        }
        private float textWidth(String s,float size)throws IOException{return font.getStringWidth(visual(s))/1000*size;}
        private void text(String s,float right,float baseline,float size,Color color)throws IOException{stream.beginText();stream.setFont(font,size);stream.setNonStrokingColor(color);String v=visual(s);stream.newLineAtOffset(right-font.getStringWidth(v)/1000*size,baseline);stream.showText(v);stream.endText();}
        private void newPage()throws IOException{
            if(stream!=null)stream.close();var p=new PDPage(new PDRectangle(width,height));doc.addPage(p);stream=new PDPageContentStream(doc,p);page++;
            text("ناب | گزارش گشت",width-margin,height-30,16,new Color(15,45,65));
            text("بازه: "+report.periodLabel()+" | تهیه: "+ReportService.persian(report.generatedAt(),true)+" | به وقت تهران",width-margin,height-49,9,Color.DARK_GRAY);
            text("صفحه "+page+" | شناسه گزارش: "+report.id(),width-margin,15,8,Color.GRAY);y=height-72;
        }
        void heading(String value,float size)throws IOException{var lines=wrap(value,width-2*margin,size);if(y<90+lines.size()*(size+4))newPage();y-=6;for(String l:lines){text(l,width-margin,y,size,new Color(15,65,75));y-=size+4;}y-=5;}
        List<String> wrap(String value,float width,float size)throws IOException{
            List<String> lines=new ArrayList<>();
            for(String paragraph:value.replace('\r',' ').split("\\n",-1)){
                String line="";
                for(String word:paragraph.split("\\s+")){
                    String candidate=line.isEmpty()?word:line+" "+word;
                    if(textWidth(candidate,size)<=width){line=candidate;continue;}
                    if(!line.isEmpty()){lines.add(line);line="";}
                    // Fallback for an identifier or a word wider than its entire column.
                    for(int cp:word.codePoints().toArray()){String next=new String(Character.toChars(cp));if(!line.isEmpty()&&textWidth(line+next,size)>width){lines.add(line);line="";}line+=next;}
                }
                lines.add(line);
            }return lines;
        }
        void paragraph(String value,float size)throws IOException{for(String l:wrap(value,width-2*margin,size)){if(y<48)newPage();text(l,width-margin,y,size,Color.DARK_GRAY);y-=size+6;}y-=8;}
        void table(String[] headers,float[] weights,List<String[]> rows)throws IOException{
            float sum=0;for(float w:weights)sum+=w;float[] widths=weights.clone();for(int i=0;i<widths.length;i++)widths[i]=weights[i]/sum*(width-2*margin);
            int headerLines=1;for(int i=0;i<headers.length;i++)headerLines=Math.max(headerLines,wrap(headers[i],widths[i]-10,8).size());
            int fullPageLines=(int)((height-72-(headerLines*12+8)-36-8)/12);
            if(y<100)newPage();drawRow(headers,widths,true,0,Integer.MAX_VALUE);
            for(String[] row:rows){List<List<String>> lines=new ArrayList<>();int max=1;for(int i=0;i<row.length;i++){var l=wrap(row[i]==null?"-":row[i],widths[i]-10,8);lines.add(l);max=Math.max(max,l.size());}
                if(max<=Math.min(8,fullPageLines)&&y-(max*12+8)<36){newPage();drawRow(headers,widths,true,0,Integer.MAX_VALUE);}
                int offset=0;while(offset<max){int available=(int)((y-36-8)/12);if(available<1){newPage();drawRow(headers,widths,true,0,Integer.MAX_VALUE);continue;}int count=Math.min(max-offset,available);drawLines(lines,widths,false,offset,count);offset+=count;if(offset<max){newPage();drawRow(headers,widths,true,0,Integer.MAX_VALUE);}}
            }y-=12;
        }
        private void drawRow(String[] values,float[] widths,boolean header,int start,int count)throws IOException{List<List<String>> lines=new ArrayList<>();int max=1;for(int i=0;i<values.length;i++){var l=wrap(values[i],widths[i]-10,8);lines.add(l);max=Math.max(max,l.size());}drawLines(lines,widths,header,start,Math.min(count,max));}
        private void drawLines(List<List<String>> lines,float[] widths,boolean header,int offset,int count)throws IOException{
            float rowHeight=count*12+8,right=width-margin;stream.setNonStrokingColor(header?new Color(21,65,78):new Color(246,249,250));stream.addRect(margin,y-rowHeight,width-2*margin,rowHeight);stream.fill();
            for(int i=0;i<widths.length;i++){for(int k=0;k<count;k++){int index=offset+k;if(index<lines.get(i).size())text(lines.get(i).get(index),right-5,y-12-k*12,8,header?Color.WHITE:new Color(30,50,60));else if(offset>0&&k==0)text(lines.get(i).getFirst(),right-5,y-12,8,new Color(30,50,60));}right-=widths[i];}
            y-=rowHeight;stream.setStrokingColor(new Color(218,228,233));stream.setLineWidth(.4f);stream.moveTo(margin,y);stream.lineTo(width-margin,y);stream.stroke();
        }
        public void close()throws IOException{if(stream!=null)stream.close();}
    }
}
