package com.mcworldexplorer.ui;

import javafx.fxml.FXML;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.lang.reflect.Modifier;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Verify injection and event contracts without starting the JavaFX toolkit or opening windows. */
class FxmlContractTest {
    @Test void mainAndMapLayoutsKeepValidFieldAndActionBindings()throws Exception {
        verify("/fxml/main.fxml");verify("/fxml/map-view.fxml");
    }
    private void verify(String resource)throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        org.w3c.dom.Document document;
        try(var input=getClass().getResourceAsStream(resource)){
            assertNotNull(input,resource);document=factory.newDocumentBuilder().parse(input);
        }
        String namespace="http://javafx.com/fxml/1";
        var controller=Class.forName(document.getDocumentElement().getAttributeNS(namespace,"controller"),false,getClass().getClassLoader());
        Map<String,Element> ids=new HashMap<>();var nodes=document.getElementsByTagName("*");
        for(int i=0;i<nodes.getLength();i++){
            var element=(Element)nodes.item(i);String id=element.getAttributeNS(namespace,"id");
            if(!id.isEmpty())assertNull(ids.put(id,element),"duplicate fx:id: "+id);
            var attributes=element.getAttributes();
            for(int j=0;j<attributes.getLength();j++){
                var attribute=attributes.item(j);String value=attribute.getNodeValue();
                if(attribute.getNodeName().startsWith("on")&&value.startsWith("#")){
                    assertTrue(Arrays.stream(controller.getDeclaredMethods()).anyMatch(method->method.getName().equals(value.substring(1))
                        &&(Modifier.isPublic(method.getModifiers())||method.isAnnotationPresent(FXML.class))
                        &&method.getParameterCount()<=1),"missing event handler "+controller.getSimpleName()+"."+value);
                }
            }
        }
        for(var field:controller.getDeclaredFields())if(field.isAnnotationPresent(FXML.class)){
            Element element=ids.get(field.getName());
            if(element==null&&field.getName().endsWith("Controller")){
                var included=ids.get(field.getName().substring(0,field.getName().length()-"Controller".length()));
                assertNotNull(included,"missing included controller: "+field.getName());
                assertEquals("include",included.getLocalName());continue;
            }
            assertNotNull(element,"missing injected element: "+field.getName());
            Class<?> type=type(element.getTagName());
            assertTrue(field.getType().isAssignableFrom(type),field.getName()+" expects "+field.getType()+" but FXML constructs "+type);
        }
    }
    private static Class<?> type(String tag)throws ClassNotFoundException {
        for(String prefix:List.of("javafx.scene.control.","javafx.scene.layout.","javafx.scene.image.")){
            try{return Class.forName(prefix+tag,false,FxmlContractTest.class.getClassLoader());}catch(ClassNotFoundException ignored){ }
        }
        throw new ClassNotFoundException(tag);
    }
}
