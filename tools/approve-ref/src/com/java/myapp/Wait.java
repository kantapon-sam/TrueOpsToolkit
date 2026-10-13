package com.java.myapp;

import java.awt.Image;
import javax.swing.*;

public class Wait extends JFrame {

    public Wait() {

        // JFrame Property
        super("Please wait"); // Title
        setSize(590, 80);
        setLocation(500, 280);
        setResizable(false);
        // Icon
        Image image = new ImageIcon("image\\custom-reports.png").getImage();
        setIconImage(image);
        // Text Label
        JLabel welcome = new JLabel("Waiting for the Chrome driver file to finish downloading successfully.");
        welcome.setBounds(45, 10, 500, 20);

        // Panel
        JPanel panel = new JPanel();
        panel.setLayout(null);
        panel.add(welcome);

        getContentPane().add(panel);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setVisible(true);

    }
}
