package com.appleyk.observer;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

/**
 * <p></p>
 *
 * @author Appleyk
 * @version v1.0
 * @blob https://blog.csdn.net/appleyk
 * @github https://github.com/kobeyk
 * @date created on 2023/3/19:22:29
 */
public class EventSource {
    String name;
    PropertyChangeSupport support = new PropertyChangeSupport(this);
    public EventSource(String name) {
        this.name = name;
    }

    public void setName(String name) {
        String oldName ;
        if (this.name == null){
            oldName = null;
        }else {
            oldName = this.name;
        }
        this.name = name;
        support.firePropertyChange("setName",oldName,name);
    }
    public void addPropertyListener(PropertyChangeListener listener){
        support.addPropertyChangeListener(listener);
    }

    public void removePropertyListener(PropertyChangeListener listener){
        support.removePropertyChangeListener(listener);
    }

    public static void main(String[] args) {
        EventSource source = new EventSource("appleyk");
        source.addPropertyListener(new EventListener());
        source.setName("tomcat");
    }
}
