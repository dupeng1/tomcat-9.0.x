package com.appleyk.observer;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * <p></p>
 *
 * @author Appleyk
 * @version v1.0
 * @blob https://blog.csdn.net/appleyk
 * @github https://github.com/kobeyk
 * @date created on 2023/3/19:22:33
 */
public class EventListener implements PropertyChangeListener {
    @Override
    public void propertyChange(PropertyChangeEvent event) {
        Object source = event.getSource();
        Object oldValue = event.getOldValue();
        Object newValue = event.getNewValue();
        String propertyName = event.getPropertyName();
        System.out.println("propertyChange");
    }
}
