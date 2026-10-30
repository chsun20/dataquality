package com.sch.order_rules.fact;

import lombok.Data;

@Data
public class Bank implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    private int age;
    private String job;
    private String marital;
    private String education;
    private String isDefault;
    private int balance;
    private String housing;
    private String loan;
    private String contact;
    private int day;
    private String month;
    private int duration;
    private int campaign;
    private int pdays;
    private int previous;
    private String poutcome;
    private String y;
}
