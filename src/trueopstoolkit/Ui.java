package trueopstoolkit;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;

final class Ui {
    static final Color NAVY=new Color(22,38,61), BG=new Color(244,247,251), TEXT=new Color(35,49,68), MUTED=new Color(94,109,127);
    static void setup() {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch(Exception ignored) {}
        useEnglish();
        Font f=new Font("Tahoma",Font.PLAIN,14);
        for(Object key:Collections.list(UIManager.getDefaults().keys())) if(UIManager.get(key) instanceof Font) UIManager.put(key,f);
        UIManager.put("Button.focus",new Color(0,0,0,0));
    }
    static void useEnglish() {
        JComponent.setDefaultLocale(Locale.ENGLISH);
        UIManager.getDefaults().setDefaultLocale(Locale.ENGLISH);
    }
    static JLabel label(String text,int size,Color color,boolean bold) {
        JLabel l=new JLabel(text); l.setFont(new Font("Tahoma",bold?Font.BOLD:Font.PLAIN,size)); l.setForeground(color); return l;
    }
    static JPanel header(String title,String subtitle) {
        JPanel p=new JPanel(new BorderLayout(0,12)); p.setBackground(NAVY); p.setBorder(new EmptyBorder(25,28,25,28));
        p.add(label(title,27,Color.WHITE,true),BorderLayout.NORTH);
        p.add(label(subtitle,14,new Color(193,210,230),false),BorderLayout.SOUTH); return p;
    }
    static JButton button(String text,Color color) {
        JButton b=new JButton(text); b.setBackground(color); b.setForeground(Color.WHITE); b.setFocusPainted(false);
        b.setFont(new Font("Tahoma",Font.BOLD,14)); b.setBorder(new CompoundBorder(new LineBorder(color),new EmptyBorder(11,18,11,18)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); return b;
    }
    static JTextArea note(String text) {
        JTextArea t=new JTextArea(text); t.setEditable(false); t.setOpaque(false); t.setLineWrap(true); t.setWrapStyleWord(true); t.setForeground(MUTED); return t;
    }
    static void showError(Component parent,Throwable error) {
        String message=error.getMessage(); if(message==null) message=error.getClass().getSimpleName();
        JOptionPane.showMessageDialog(parent,message,"TrueOps Toolkit",JOptionPane.ERROR_MESSAGE);
    }
    static void open(Path path,Component parent) {
        try { if(!Files.exists(path)) throw new java.io.IOException("File not found: "+path.getFileName()); Desktop.getDesktop().open(path.toFile()); }
        catch(Exception ex){showError(parent,ex);}
    }
    static void snapshot(JFrame frame,Path file,boolean maskFields) throws Exception {
        Container content=frame.getContentPane();
        BufferedImage image=new BufferedImage(content.getWidth(),content.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=image.createGraphics();
        try {
            content.printAll(graphics);
            if(maskFields) mask(content,content,graphics);
        } finally {graphics.dispose();}
        Files.createDirectories(file.getParent()); ImageIO.write(image,"png",file.toFile());
    }
    /** Mask only the rendered pixels; never change a document or copy a password. */
    private static void mask(Container current,Container content,Graphics2D graphics){
        for(Component component:current.getComponents()) {
            if(!component.isVisible())continue;
            if(component instanceof JTextField){
                JTextField field=(JTextField)component;
                Point position=SwingUtilities.convertPoint(field,0,0,content);
                Graphics2D overlay=(Graphics2D)graphics.create(position.x,position.y,field.getWidth(),field.getHeight());
                try {
                    overlay.clip(field.getVisibleRect());
                    overlay.setColor(field.getBackground());
                    overlay.fillRect(0,0,field.getWidth(),field.getHeight());
                    if(field.getBorder()!=null)field.getBorder().paintBorder(field,overlay,0,0,field.getWidth(),field.getHeight());
                } finally {overlay.dispose();}
            }
            if(component instanceof Container)mask((Container)component,content,graphics);
        }
    }
}
