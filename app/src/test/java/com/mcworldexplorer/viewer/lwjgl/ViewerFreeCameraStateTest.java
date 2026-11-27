package com.mcworldexplorer.viewer.lwjgl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ViewerFreeCameraStateTest {
    private static ViewerFreeCameraState camera() { return new ViewerFreeCameraState(-20, -64, 30, 0, 0, 16); }
    private static double distance(ViewerFreeCameraState a, ViewerFreeCameraState b) { return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2)); }
    @Test void normalAndLowFrameRatesTravelTheSameDistance() {
        var fast = camera(); var slow = camera();
        for (int i=0;i<60;i++) fast=fast.move(1,0,0,false,1d/60);
        for (int i=0;i<10;i++) slow=slow.move(1,0,0,false,.1);
        assertEquals(16,distance(camera(),fast),1e-9); assertEquals(fast.z(),slow.z(),1e-9);
    }
    @Test void diagonalsAndVerticalCombinationsHaveNormalizedSpeed() {
        var c=camera(); assertEquals(1.6,distance(c,c.move(1,1,1,false,.1)),1e-9);
        var tilted=c.turn(200,-100); assertEquals(1.6,distance(tilted,tilted.move(1,0,1,false,.1)),1e-9);
        assertEquals(6.4,distance(c,c.move(1,1,0,true,.1)),1e-9);
    }
    @Test void lookingRightAndUpChangesForwardDirectionAndVerticalMotionUsesWorldY() {
        var c=camera().turn(100,-100); var forward=c.move(1,0,0,false,.1);
        assertTrue(forward.x()>c.x()); assertTrue(forward.y()>c.y()); assertTrue(forward.z()<c.z());
        var up=c.move(0,0,1,false,.1); assertEquals(c.x(),up.x()); assertEquals(c.z(),up.z()); assertEquals(c.y()+1.6,up.y(),1e-9);
        assertTrue(c.move(0,0,-1,false,.1).y()<c.y());
    }
    @Test void longPauseInvalidDeltaAndScrollCannotCreateRunawayCamera() {
        var c=camera(); assertEquals(1.6,distance(c,c.move(1,0,0,false,30)),1e-9);
        assertEquals(c,c.move(1,0,0,false,Double.NaN)); assertEquals(c,c.turn(Double.NaN,2));
        assertEquals(128,c.scroll(1000).speed()); assertEquals(1,c.scroll(-1000).speed());
        var edge=new ViewerFreeCameraState(1e6,1e6,-1e6,Math.PI/2,0,128);
        assertTrue(edge.move(1,0,1,true,.1).x()<=1e6);
    }
}
