package top.weixiansen574.hybridfilexfer.core;

import java.util.LinkedList;

import top.weixiansen574.hybridfilexfer.core.bean.TransferEvent;


public class TransferEventDeque {
    private boolean isRelease = false;
    private final LinkedList<TransferEvent> events = new LinkedList<>();
    public void addEvent(TransferEvent event){
        synchronized (events) {
            if (isRelease){
                throw new IllegalStateException("released");
            }
            events.add(event);
            events.notify();
        }
    }

    public TransferEvent remove(){
        synchronized (events){
            /*while (events.isEmpty()) {
                try {
                    if (isRelease) {
                        return null;
                    }
                    events.wait();
                } catch (InterruptedException e) {
                    return null;
                }
            }
            // Once out of the loop, the list is guaranteed to have at least one element
            return events.removeFirst();*/
            if (events.isEmpty()){
                try {
                    if (isRelease){
                        return null;
                    }
                    events.wait();
                    if (events.isEmpty()){
                        return null;
                    }
                    return events.removeFirst();
                } catch (InterruptedException e) {
                    return null;
                }
            } else {
                return events.removeFirst();
            }
        }
    }

    public void release(){
        synchronized (events) {
            isRelease = true;
            events.notify();
        }
    }
}
