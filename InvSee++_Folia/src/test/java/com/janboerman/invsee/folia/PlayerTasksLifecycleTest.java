package com.janboerman.invsee.folia;

import io.papermc.paper.threadedregions.scheduler.*;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PlayerTasks implementation, deterministic API proxies; not live Folia. */
class PlayerTasksLifecycleTest {
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
    static final class Fixture {
        Consumer<ScheduledTask> run;
        Runnable retired;
        boolean online=true, reject, nullTask;
        int schedulingCalls;
        Player current;
        final ScheduledTask task=proxy(ScheduledTask.class,(p,m,a)->null);
        final EntityScheduler scheduler=proxy(EntityScheduler.class,(p,m,a)->{
            if (!m.getName().equals("run")) throw new AssertionError("Unexpected scheduling fallback: "+m);
            schedulingCalls++;
            if(reject)throw new RejectedExecutionException("test scheduler rejection");
            run=(Consumer<ScheduledTask>)a[1]; retired=(Runnable)a[2];
            return nullTask?null:task;
        });
        final Server server=proxy(Server.class,(p,m,a)->{
            if(m.getName().equals("getPlayer"))return current;
            throw new AssertionError("Unexpected global/server call: "+m);
        });
        final Player player=proxy(Player.class,(p,m,a)->switch(m.getName()){
            case "getScheduler" -> scheduler;
            case "getUniqueId" -> new UUID(1,2);
            case "getServer" -> server;
            case "isOnline" -> online;
            default -> throw new AssertionError("Unexpected player access: "+m);
        });
        Fixture(){current=player;}
        void execute(){run.accept(task);}
    }
    // One test owns the irreversible classloader-scoped shutdown; no reflective
    // reset or dependence on test ordering, and no production lifecycle bypass.
    @Test void retirementIdentityRejectionAndShutdownDrain() throws Exception {
        AtomicInteger actions=new AtomicInteger();
        Fixture retired=new Fixture();
        var r=PlayerTasks.call(null,retired.player,actions::incrementAndGet);
        retired.retired.run(); retired.execute();
        assertThrows(CancellationException.class,r::join); assertEquals(0,actions.get());
        Fixture absent=new Fixture(); absent.nullTask=true;
        assertThrows(CancellationException.class,()->PlayerTasks.call(null,absent.player,actions::incrementAndGet).join());
        Fixture rejected=new Fixture(); rejected.reject=true;
        assertThrows(CompletionException.class,()->PlayerTasks.call(null,rejected.player,actions::incrementAndGet).join());
        Fixture offline=new Fixture(); var o=PlayerTasks.call(null,offline.player,actions::incrementAndGet);
        offline.online=false; offline.execute(); assertThrows(CancellationException.class,o::join);
        Fixture replaced=new Fixture(); var p=PlayerTasks.call(null,replaced.player,actions::incrementAndGet);
        replaced.current=null; replaced.execute(); assertThrows(CancellationException.class,p::join);
        Fixture normal=new Fixture(); var n=PlayerTasks.call(null,normal.player,actions::incrementAndGet);
        normal.execute(); normal.execute(); normal.retired.run(); assertEquals(1,n.join()); assertEquals(1,actions.get());
        Fixture queued=new Fixture(); var q=PlayerTasks.call(null,queued.player,actions::incrementAndGet);
        Fixture running=new Fixture(); CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        var runningResult=PlayerTasks.call(null,running.player,()->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("release timeout");}catch(InterruptedException e){throw new RuntimeException(e);}return actions.incrementAndGet();});
        Thread owner=new Thread(running::execute,"deterministic-owner"); owner.start();
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        CompletableFuture<Void> drained=PlayerTasks.stop();
        try {
            assertFalse(drained.isDone()); assertThrows(CancellationException.class,q::join);
            queued.execute(); assertEquals(1,actions.get());
            assertThrows(CancellationException.class,runningResult::join);
            Fixture after=new Fixture();assertThrows(CancellationException.class,()->PlayerTasks.call(null,after.player,actions::incrementAndGet).join());
            assertEquals(0,after.schedulingCalls);
        } finally {release.countDown();owner.join(5000);}
        drained.get(5,TimeUnit.SECONDS);assertEquals(2,actions.get());assertFalse(owner.isAlive());
    }
}
