package trueopstoolkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** The existing module formats, edited without logging credential values. */
final class ModuleSettings {
    final ModuleSpec module;
    final Path directory;
    String search="", description="", approvalDescription="", rejectionDescription="";
    int searchDelayMs=2000;
    final List<String[]> rows=new ArrayList<String[]>();
    private final List<String> extraLines=new ArrayList<String>();
    private byte[] originalLogin,originalReferences;

    ModuleSettings(Path home,ModuleSpec module) {this.module=module;directory=module.directory(home);}

    void load() throws IOException {
        byte[] login=read(SettingsFiles.resolve(directory));
        byte[] references=hasReferences()?read(directory.resolve("Ref_No.csv")):null;
        String loginText=decode(login),refText=decode(references);
        List<String[]> loadedRows=new ArrayList<String[]>();
        int number=0;
        for(String line:refText.split("\\r?\\n",-1)) {
            number++;
            if(line.trim().isEmpty())continue;
            String[] cells=line.split(",",columnCount());
            if(cells.length!=columnCount()) {
                int present=cells.length;cells=Arrays.copyOf(cells,columnCount());
                for(int i=present;i<cells.length;i++)cells[i]="";
            }
            loadedRows.add(cells);
        }
        search=module==ModuleSpec.TK07?"TK07":"";description="";approvalDescription="";rejectionDescription="";searchDelayMs=2000;extraLines.clear();
        for(String line:loginText.split("\\r?\\n",-1)) {
            if(line.isEmpty())continue;
            String[] pair=line.split(",",2);
            String key=pair[0].trim();String value=pair.length==2?pair[1]:"";
            if(SharedCredentialsMigration.isCredentialKey(key))continue;
            else if("Search".equals(key))search=value;
            else if("Description".equals(key))description=value;
            else if("SearchDelayMs".equals(key)){try{searchDelayMs=Integer.parseInt(value.trim());}catch(NumberFormatException invalid){throw new IOException("Search wait must be a whole number of milliseconds between 1000 and 10000.");}}
            else if(module==ModuleSpec.TK07 && "Description_Approve".equals(key))approvalDescription=value;
            else if(module==ModuleSpec.TK07 && "Description_Reject".equals(key))rejectionDescription=value;
            else extraLines.add(line);
        }
        rows.clear();rows.addAll(loadedRows);
        originalLogin=login;originalReferences=references;
    }

    boolean hasReferences(){return module==ModuleSpec.REF || module==ModuleSpec.DELETE;}
    int columnCount(){return module==ModuleSpec.REF?3:2;}

    void validate(boolean forRun) throws IOException {
        singleLine(search,"Search");singleLine(description,"Description");
        singleLine(approvalDescription,"Approval comment");singleLine(rejectionDescription,"Rejection comment");
        if(searchDelayMs<1000 || searchDelayMs>10000)throw new IOException("Search wait must be between 1000 and 10000 milliseconds.");
        if(forRun && (module==ModuleSpec.APPROVE || module==ModuleSpec.TK07)) {
            if(search.trim().isEmpty())throw new IOException("Enter search criteria to identify the records to approve before starting.");
            String[] terms=search.split("&&",-1);
            int limit=module==ModuleSpec.APPROVE?4:2;
            if(terms.length>limit)throw new IOException("Search supports up to "+limit+" conditions.");
            for(String term:terms)if(term.trim().isEmpty())throw new IOException("Enter a search condition on both sides of &&.");
        }
        int index=0;
        for(String[] row:rows) {
            index++;
            if(row.length!=columnCount())throw new IOException("The table has an incorrect number of columns.");
            for(int c=0;c<row.length;c++) {
                singleLine(row[c],"Table row "+index);
                if(row[c].trim().isEmpty() && !(module==ModuleSpec.REF && c==2))throw new IOException("Complete all required fields in row "+index+", or remove the row.");
                if(c<row.length-1 && row[c].contains(","))throw new IOException("Ref No. and Type must not contain commas (row "+index+").");
            }
        }
        if(forRun && hasReferences() && rows.isEmpty())throw new IOException("Add at least one record before starting.");
    }

    void save() throws IOException {
        validate(false);
        Path login=SettingsFiles.resolve(directory),references=directory.resolve("Ref_No.csv");
        if(!Arrays.equals(originalLogin,read(login)) || (hasReferences() && !Arrays.equals(originalReferences,read(references))))
            throw new IOException("The files changed outside this window. Click Reload saved settings before saving over them.");
        StringBuilder content=new StringBuilder();
        if(module==ModuleSpec.TK07)content.append("Search,").append(search).append("\r\nDescription_Approve,").append(approvalDescription).append("\r\nDescription_Reject,").append(rejectionDescription).append("\r\n");
        else if(module==ModuleSpec.APPROVE)content.append("Search,").append(search).append("\r\nDescription,").append(description).append("\r\n");
        else {
            if(!search.isEmpty())content.append("Search,").append(search).append("\r\n");
            if(!description.isEmpty())content.append("Description,").append(description).append("\r\n");
        }
        if(module!=ModuleSpec.DELETE)content.append("SearchDelayMs,").append(searchDelayMs).append("\r\n");
        for(String line:extraLines)content.append(line).append("\r\n");
        byte[] newLogin=content.toString().getBytes(StandardCharsets.UTF_8);
        StringBuilder refs=new StringBuilder();
        for(String[] row:rows)refs.append(String.join(",",row)).append("\r\n");
        byte[] newReferences=refs.toString().getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(directory);
        if(hasReferences())writeAtomic(references,newReferences);
        try {writeAtomic(login,newLogin);}
        catch(IOException failure) {
            if(hasReferences())try {
                if(originalReferences==null)Files.deleteIfExists(references);else writeAtomic(references,originalReferences);
            } catch(IOException rollback) {failure.addSuppressed(rollback);}
            throw failure;
        }
        originalLogin=newLogin;if(hasReferences())originalReferences=newReferences;
    }

    private static void singleLine(String value,String name) throws IOException {
        if(value==null || value.indexOf('\r')>=0 || value.indexOf('\n')>=0 || value.indexOf('\0')>=0)throw new IOException(name+" must be a single line of text.");
    }
    private static byte[] read(Path file) throws IOException {return Files.exists(file)?Files.readAllBytes(file):null;}
    private static String decode(byte[] bytes) throws IOException {
        if(bytes==null)return "";
        String value=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        return value.startsWith("\uFEFF")?value.substring(1):value;
    }
    private static void writeAtomic(Path target,byte[] content) throws IOException {
        Path temporary=Files.createTempFile(target.getParent(),".settings-",".tmp");
        try {
            Files.write(temporary,content);
            try {Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ex){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }
}
