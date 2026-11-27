package com.mcworldexplorer.voxel.resource;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ModelPluginWireTest {
    private static DataInputStream frame(String text)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var output=new DataOutputStream(bytes)){byte[] body=text.getBytes(StandardCharsets.UTF_8);output.writeInt(body.length);output.write(body);}return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }
    @Test void resourceScopeAndWrappedFailuresSurviveRoundtrip()throws Exception {
        for(var scope:ResourceAccessException.Scope.values()){
            var original=new ResourceAccessException(ResourceAccessException.Reason.LIMIT_EXCEEDED,"bounded fixture",scope);
            JsonObject response=ModelPluginWire.error(new java.lang.reflect.UndeclaredThrowableException(original));
            var restored=assertThrows(ResourceAccessException.class,()->ModelPluginWire.check(response));
            assertEquals(scope,restored.scope());assertEquals(original.reason(),restored.reason());assertEquals(original.getMessage(),restored.getMessage());
        }
    }
    @Test void malformedDuplicateDeepOversizedOrIncompleteFramesAreRejected()throws Exception {
        for(String text:new String[]{"{\"a\":1,\"a\":2}","{}{}","{\"a\":"+"[".repeat(100)+"0"+"]".repeat(100)+"}","{bad}"})
            try(var input=frame(text)){assertThrows(IOException.class,()->ModelPluginWire.read(input));}
        for(int size:new int[]{-1,ModelPluginWire.MAX_FRAME+1,100}){
            var bytes=new ByteArrayOutputStream();try(var output=new DataOutputStream(bytes)){output.writeInt(size);}
            try(var input=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))){assertThrows(IOException.class,()->ModelPluginWire.read(input));}
        }
    }
}
