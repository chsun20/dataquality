package com.sch.oder_rules.fact;

public class Order {
    private int amout;//订单原价⾦额
    private int score;//积分
    public int getAmout() { return amout; }
    public void setAmout(int amout) { this.amout = amout; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }

    @Override public String toString() {
        return "Order{" + "amout=" + amout + ", score=" + score + '}';
    }
}
