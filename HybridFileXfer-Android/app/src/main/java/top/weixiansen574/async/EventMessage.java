package top.weixiansen574.async;

public class EventMessage {
    private final int what;
    private final Object[] objects;
    public  EventMessage(Object[] objects,int what){
        this.what = what;
        this.objects = objects;
    }
    public EventMessage(int what,Object... objects){
        this.what = what;
        this.objects = objects;
    }

    public EventMessage(int what){
        this.what = what;
        this.objects = new Object[0];
    }

    public <T> T getObject(int index,Class<T> clazz){
        return clazz.cast(objects[index]);
    }

    public int getWhat() {
        return what;
    }

    public Object[] getObjects(){
        return objects;
    }
}
