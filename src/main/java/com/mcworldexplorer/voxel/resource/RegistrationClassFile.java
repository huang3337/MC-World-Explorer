package com.mcworldexplorer.voxel.resource;

import java.io.*;
import java.util.*;

/** Structural class-file decoding only. No class loader, reflection, interpreter or method invocation. */
public final class RegistrationClassFile {
    public record Member(String owner,String name,String descriptor) { }
    public record Type(String internalName) { }
    public record IntVector(int x,int y,int z) { }
    public record Instruction(int offset,int opcode,Object operand) { }
    public record Branch(int target) { }
    public record Lambda(Member implementation) { }
    public record ConstructorReference(Member implementation) { }
    public record Method(String name,String descriptor,int access,boolean exceptionHandlers,byte[] code) {
        public Method {code=code==null?null:code.clone();}
        @Override public byte[] code(){return code==null?null:code.clone();}
    }
    private record Link(int first,int second) { }
    private record Field(String name,String descriptor,int access) { }
    private final Object[] pool;
    private final int[] tags;
    private final String name,parent;
    private final List<String> interfaces;
    private final List<Method> methods;
    private final List<Field> fields;
    private final List<int[]> bootstraps;
    private RegistrationClassFile(byte[] data)throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(data))){
            if(in.readInt()!=0xCAFEBABE)throw invalid("文件头无效");
            in.readUnsignedShort();int major=in.readUnsignedShort();if(major<45||major>100)throw invalid("类文件版本不在识别范围");
            int count=in.readUnsignedShort();if(count<1)throw invalid("常量池为空");pool=new Object[count];tags=new int[count];
            for(int i=1;i<count;i++){
                int tag=in.readUnsignedByte();tags[i]=tag;
                switch(tag){
                    case 1->pool[i]=in.readUTF();case 3->pool[i]=in.readInt();case 4->pool[i]=in.readFloat();
                    case 5,6->{pool[i]=tag==5?(Object)in.readLong():in.readDouble();if(++i>=count)throw invalid("双槽常量越界");}
                    case 7,8,16,19,20->pool[i]=in.readUnsignedShort();
                    case 9,10,11,12,17,18->pool[i]=new Link(in.readUnsignedShort(),in.readUnsignedShort());
                    case 15->pool[i]=new Link(in.readUnsignedByte(),in.readUnsignedShort());
                    default->throw invalid("未知常量池类型："+tag);
                }
            }
            in.readUnsignedShort();name=type(in.readUnsignedShort());int superIndex=in.readUnsignedShort();parent=superIndex==0?"":type(superIndex);
            int interfaceCount=in.readUnsignedShort();List<String> interfaceNames=new ArrayList<>();for(int i=0;i<interfaceCount;i++)interfaceNames.add(type(in.readUnsignedShort()));interfaces=List.copyOf(interfaceNames);
            List<Field> fs=new ArrayList<>();int fieldCount=in.readUnsignedShort();
            for(int i=0;i<fieldCount;i++){int access=in.readUnsignedShort();fs.add(new Field(utf(in.readUnsignedShort()),utf(in.readUnsignedShort()),access));skipAttributes(in);}
            fields=List.copyOf(fs);List<Method> ms=new ArrayList<>();int methodCount=in.readUnsignedShort();
            for(int i=0;i<methodCount;i++){
                int access=in.readUnsignedShort();String n=utf(in.readUnsignedShort()),d=utf(in.readUnsignedShort());byte[] code=null;boolean exceptions=false;
                int attributes=in.readUnsignedShort();
                for(int a=0;a<attributes;a++){
                    String attribute=utf(in.readUnsignedShort());int length=length(in,RegistrationClass.MAX_BYTES);byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw invalid("属性被截断");
                    if(attribute.equals("Code")){
                        if(code!=null)throw invalid("重复Code属性");
                        try(var body=new DataInputStream(new ByteArrayInputStream(bytes))){
                            body.readUnsignedShort();body.readUnsignedShort();int size=length(body,65535);code=body.readNBytes(size);if(code.length!=size)throw invalid("方法被截断");
                            int handlers=body.readUnsignedShort();exceptions=handlers!=0;body.skipNBytes(handlers*8L);
                            skipAttributes(body);if(body.available()!=0)throw invalid("Code属性尾部无效");
                        }
                    }
                }
                ms.add(new Method(n,d,access,exceptions,code));
            }
            methods=List.copyOf(ms);List<int[]> bootstrapList=new ArrayList<>();int attributes=in.readUnsignedShort();boolean seen=false;
            for(int i=0;i<attributes;i++){
                String attribute=utf(in.readUnsignedShort());int length=length(in,RegistrationClass.MAX_BYTES);
                if(!attribute.equals("BootstrapMethods")){in.skipNBytes(length);continue;}
                if(seen)throw invalid("重复BootstrapMethods属性");seen=true;byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw invalid("启动元信息被截断");
                try(var body=new DataInputStream(new ByteArrayInputStream(bytes))){
                    int n=body.readUnsignedShort();for(int b=0;b<n;b++){int method=body.readUnsignedShort(),args=body.readUnsignedShort();int[] values=new int[args+1];values[0]=method;for(int a=1;a<values.length;a++)values[a]=body.readUnsignedShort();bootstrapList.add(values);}
                    if(body.available()!=0)throw invalid("启动元信息尾部无效");
                }
            }
            bootstraps=List.copyOf(bootstrapList);if(in.available()!=0)throw invalid("类文件尾部无效");
        }catch(EOFException|IndexOutOfBoundsException|ClassCastException invalid){throw new IOException("注册类结构损坏",invalid);}
    }
    public static RegistrationClassFile read(RegistrationClass input)throws IOException {
        var result=new RegistrationClassFile(input.bytes());if(!result.name.equals(input.internalName()))throw invalid("类文件身份与查询名称不符");return result;
    }
    public String name(){return name;}
    public String parent(){return parent;}
    public List<String> interfaces(){return interfaces;}
    public List<Method> methods(){return methods;}
    /** Structural field lookup for resolving inherited static/property references. */
    boolean declaresField(String name,String descriptor){return fields.stream().anyMatch(f->f.name().equals(name)&&f.descriptor().equals(descriptor));}
    /** Immutable structural strings, for recognizing literal resource templates; never evaluated. */
    public List<String> strings(){List<String> result=new ArrayList<>();for(int i=1;i<tags.length;i++)if(tags[i]==1)result.add((String)pool[i]);return List.copyOf(result);}
    /** Decode inert instructions; switches, subroutines, wide and reserved instructions fail explicitly. */
    public List<Instruction> instructions(Method method)throws IOException {
        return instructions(method,false);
    }
    /** Structural scan only: exposes ATHROW as a terminal opcode, never executes/catches it.
     * Consumers must reject it inside any recognized expression or unconditional region. */
    public List<Instruction> instructionsWithThrows(Method method)throws IOException {
        return instructions(method,true);
    }
    /** Literal fragments of recognized JDK StringConcatFactory sites, indexed by instruction offset.
     * Only String arguments/constants are accepted; n arguments yield n+1 immutable fragments.
     * This reads bootstrap metadata, never links a call site or evaluates an argument/target class.
     * Unknown sites are absent. Bounds: 32 arguments, 4096 literal chars/site, 256 sites/65536 chars. */
    public Map<Integer,List<String>> stringConcatenations(Method method)throws IOException {
        if(!methods.contains(method))throw invalid("字符串模板方法不属于当前类");
        var result=new LinkedHashMap<Integer,List<String>>();int characters=0;byte[] code=method.code();
        for(var instruction:instructionsWithThrows(method))if(instruction.opcode()==186){
            var fragments=concatFragments(u2(code,instruction.offset()+1));if(fragments.isEmpty())continue;
            var value=fragments.orElseThrow();characters+=value.stream().mapToInt(String::length).sum();
            if(result.size()>=256||characters>65536)throw invalid("字符串模板索引超过预算");result.put(instruction.offset(),value);
        }return Map.copyOf(result);
    }
    private Optional<List<String>> concatFragments(int index)throws IOException {
        if(tag(index)!=18)throw invalid("动态字符串调用引用无效");var dynamic=(Link)pool[index];
        if(dynamic.first<0||dynamic.first>=bootstraps.size()||tag(dynamic.second)!=12)throw invalid("动态字符串启动元信息无效");
        int[] values=bootstraps.get(dynamic.first);if(values.length==0||tag(values[0])!=15)return Optional.empty();
        var handle=(Link)pool[values[0]];var bootstrap=member(handle.second);
        if(handle.first!=6||!bootstrap.owner.equals("java/lang/invoke/StringConcatFactory"))return Optional.empty();
        String prefix="(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;",suffix=")Ljava/lang/invoke/CallSite;";
        boolean constants=bootstrap.name.equals("makeConcatWithConstants")&&bootstrap.descriptor.equals(prefix+"Ljava/lang/String;[Ljava/lang/Object;"+suffix);
        if(!constants&&!(bootstrap.name.equals("makeConcat")&&bootstrap.descriptor.equals(prefix+suffix)))return Optional.empty();
        String descriptor=utf(((Link)pool[dynamic.second]).second),output=")Ljava/lang/String;",argument="Ljava/lang/String;";
        if(!descriptor.startsWith("(")||!descriptor.endsWith(output))return Optional.empty();
        String parameters=descriptor.substring(1,descriptor.length()-output.length());
        if(!parameters.replace(argument,"").isEmpty())return Optional.empty();int count=parameters.length()/argument.length();
        if(count>32)throw invalid("字符串模板参数超过32项");
        if(!constants){if(values.length!=1)throw invalid("无常量字符串模板含额外启动参数");return Optional.of(Collections.nCopies(count+1,""));}
        if(values.length<2||values.length>34||!(constant(values[1]) instanceof String recipe))throw invalid("字符串模板配方缺失或超限");
        if(recipe.length()>4096)throw invalid("字符串模板配方超过4096字符");
        var pieces=new ArrayList<String>();var current=new StringBuilder();int constantIndex=2,total=0;
        for(int p=0;p<recipe.length();p++){
            char c=recipe.charAt(p);
            if(c=='\u0001'){pieces.add(current.toString());current.setLength(0);}
            else if(c=='\u0002'){
                if(constantIndex>=values.length)throw invalid("字符串模板常量标记缺少参数");
                Object value=constant(values[constantIndex++]);if(!(value instanceof String text))return Optional.empty();
                total+=text.length();current.append(text);
            }else{total++;current.append(c);}
            if(total>4096||pieces.size()>32)throw invalid("字符串模板片段超过预算");
        }
        if(pieces.size()!=count||constantIndex!=values.length)throw invalid("字符串模板参数/常量标记数量不符");pieces.add(current.toString());return Optional.of(List.copyOf(pieces));
    }
    private List<Instruction> instructions(Method method,boolean exposeThrows)throws IOException {
        byte[] code=method.code();if(code==null)return List.of();if(method.exceptionHandlers())throw invalid("注册表达式含异常控制流");List<Instruction> result=new ArrayList<>();
        for(int p=0;p<code.length;){
            int offset=p,op=Byte.toUnsignedInt(code[p++]);Object value=null;int length=1;
            if(op>=168&&op<=171||op==191&&!exposeThrows||op==196||op>=200)throw invalid("方法含不支持的控制流或指令");
            if(op>=2&&op<=8)value=op-3;
            else if(op==9||op==10)value=(long)(op-9);
            else if(op>=11&&op<=13)value=(float)(op-11);
            else if(op==14||op==15)value=(double)(op-14);
            else if(op==16){length=2;if(p>=code.length)throw invalid("指令被截断");value=(int)code[p];}
            else if(op==17){length=3;value=(int)(short)u2(code,p);}
            else if(op==18){length=2;if(p>=code.length)throw invalid("指令被截断");value=constant(Byte.toUnsignedInt(code[p]));}
            else if(op==19||op==20){length=3;value=constant(u2(code,p));}
            else if(op>=178&&op<=185){length=op==185?5:3;value=member(u2(code,p));}
            else if(op==186){length=5;value=lambda(u2(code,p));}
            else if(op>=153&&op<=167||op==198||op==199){length=3;int target=offset+(short)u2(code,p);if(target<0||target>=code.length)throw invalid("跳转目标越界");value=new Branch(target);}
            else if(op==187||op==189||op==192||op==193){length=3;value=new Type(type(u2(code,p)));}
            else if(op==197)throw invalid("多维数组不是注册常量表达式");
            else if(op>=21&&op<=25||op>=54&&op<=58||op==188){length=2;if(p>=code.length)throw invalid("局部变量指令被截断");value=Byte.toUnsignedInt(code[p]);}
            else if(op==132)length=3;
            else if(op>195)throw invalid("未知指令");
            if(offset+length>code.length)throw invalid("指令被截断");result.add(new Instruction(offset,op,value));p=offset+length;
        }
        Set<Integer> offsets=new HashSet<>();for(var instruction:result)offsets.add(instruction.offset());
        for(var instruction:result)if(instruction.operand() instanceof Branch branch&&!offsets.contains(branch.target()))throw invalid("跳转目标不是指令边界");
        return List.copyOf(result);
    }
    /** A tail entered only after all conditional/loop edges. No branch or early return may bypass it. */
    public List<Instruction> unconditionalTail(Method method)throws IOException {
        var code=instructions(method);int start=0;Map<Integer,Integer> indices=new HashMap<>();for(int i=0;i<code.size();i++)indices.put(code.get(i).offset(),i);
        for(int i=0;i<code.size();i++)if(code.get(i).operand() instanceof Branch branch){
            start=Math.max(start,Math.max(i+1,indices.get(branch.target())));
        }
        for(int i=0;i<start;i++)if(code.get(i).opcode()>=172&&code.get(i).opcode()<=177)throw invalid("注册尾段可被提前返回绕过");
        return List.copyOf(code.subList(start,code.size()));
    }
    /** Literal constructor arguments only, not an evaluation of the object or its initializer. */
    public Map<Member,List<String>> literalStringObjects(String objectType)throws IOException {
        RegistrationClass.checkName(objectType);String descriptor="L"+objectType+";";
        var initializer=methods.stream().filter(m->m.name().equals("<clinit>")&&m.descriptor().equals("()V")).findFirst();if(initializer.isEmpty())return Map.of();
        var code=unconditionalTail(initializer.orElseThrow());Map<Member,List<String>> result=new HashMap<>();Set<Member> assigned=new HashSet<>();
        for(int i=0;i<code.size();i++){
            var instruction=code.get(i);if(instruction.opcode()!=179||!(instruction.operand() instanceof Member target)||!target.owner().equals(name)||!target.descriptor().equals(descriptor))continue;
            if(fields.stream().noneMatch(f->f.name().equals(target.name())&&f.descriptor().equals(descriptor)&&(f.access()&0x18)==0x18))continue;
            if(!assigned.add(target))throw invalid("final静态字符串构造对象被重复赋值");
            if(i<4||!(code.get(i-1).operand() instanceof Member constructor)||code.get(i-1).opcode()!=183||!constructor.owner().equals(objectType)||!constructor.name().equals("<init>"))continue;
            int arity=constructor.descriptor().equals("(Ljava/lang/String;)V")?1:constructor.descriptor().equals("(Ljava/lang/String;Ljava/lang/String;)V")?2:0;if(arity==0||i<arity+3)continue;
            int start=i-arity-3;if(code.get(start).opcode()!=187||!(code.get(start).operand() instanceof Type type)||!type.internalName().equals(objectType)||code.get(start+1).opcode()!=89)continue;
            List<String> arguments=new ArrayList<>();for(int j=0;j<arity;j++){var arg=code.get(start+2+j);if((arg.opcode()!=18&&arg.opcode()!=19)||!(arg.operand() instanceof String value)){arguments.clear();break;}arguments.add(value);}
            if(arguments.size()==arity){result.put(target,List.copyOf(arguments));if(result.size()>1024)throw invalid("字符串对象注册超过1024项");}
        }return Map.copyOf(result);
    }

    /** Recognize only a final static vector assigned from three literal ints and a known (III)V constructor. */
    public Optional<IntVector> constantVector(String fieldName,String vectorType)throws IOException {
        var field=fields.stream().filter(f->f.name.equals(fieldName)).findFirst().orElseThrow(()->invalid("注册字段不存在："+fieldName));
        if((field.access&0x18)!=0x18||!field.descriptor.equals("L"+vectorType+";"))throw invalid("注册字段不是final static向量");
        var initializer=methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow(()->invalid("没有静态常量初始化器"));
        var code=instructions(initializer);if(code.stream().anyMatch(i->i.operand() instanceof Branch))throw invalid("向量初始化含条件控制流");IntVector found=null;int assignments=0;
        for(int i=0;i<code.size();i++){
            var end=code.get(i);if(end.opcode!=179||!end.operand.equals(new Member(name,fieldName,field.descriptor)))continue;
            if(++assignments>1)throw invalid("注册常量重复赋值");
            if(i<6)throw invalid("注册向量不是字面量表达式");var start=code.get(i-6);
            if(start.opcode!=187||!start.operand.equals(new Type(vectorType))||code.get(i-5).opcode!=89||
                    !(code.get(i-4).operand instanceof Integer x)||!(code.get(i-3).operand instanceof Integer y)||!(code.get(i-2).operand instanceof Integer z)||
                    code.get(i-1).opcode!=183||!code.get(i-1).operand.equals(new Member(vectorType,"<init>","(III)V")))throw invalid("注册向量不是已识别的三整数构造");
            found=new IntVector(x,y,z);
        }
        return Optional.ofNullable(found);
    }
    private Object lambda(int index)throws IOException {
        if(tag(index)!=18)throw invalid("动态调用引用无效");var dynamic=(Link)pool[index];if(dynamic.first<0||dynamic.first>=bootstraps.size())throw invalid("启动索引越界");
        int[] values=bootstraps.get(dynamic.first);if(values.length<3||tag(values[0])!=15)return null;
        var handle=(Link)pool[values[0]];var bootstrap=member(handle.second);
        if(!bootstrap.owner.equals("java/lang/invoke/LambdaMetafactory")||!bootstrap.name.equals("metafactory")||tag(values[2])!=15)return null;
        var implementation=(Link)pool[values[2]];if(implementation.first==8){var constructor=member(implementation.second);return constructor.name.equals("<init>")?new ConstructorReference(constructor):null;}if(implementation.first!=6)return null;return new Lambda(member(implementation.second));
    }
    private Object constant(int index)throws IOException {
        int tag=tag(index);return switch(tag){case 3,4,5,6->pool[index];case 8->utf((Integer)pool[index]);case 7->new Type(type(index));default->throw invalid("不是已识别的数值、字符串或类型常量");};
    }
    private Member member(int index)throws IOException {
        int tag=tag(index);if(tag!=9&&tag!=10&&tag!=11)throw invalid("成员引用类型无效");var ref=(Link)pool[index];
        if(tag(ref.second)!=12)throw invalid("成员名称描述符无效");var nt=(Link)pool[ref.second];return new Member(type(ref.first),utf(nt.first),utf(nt.second));
    }
    private String type(int index)throws IOException {if(tag(index)!=7)throw invalid("类型索引无效");return utf((Integer)pool[index]);}
    private String utf(int index)throws IOException {if(tag(index)!=1)throw invalid("字符串索引无效");return (String)pool[index];}
    private int tag(int index)throws IOException {if(index<=0||index>=tags.length)throw invalid("常量索引越界");return tags[index];}
    private void skipAttributes(DataInputStream in)throws IOException {int n=in.readUnsignedShort();for(int i=0;i<n;i++){utf(in.readUnsignedShort());in.skipNBytes(length(in,RegistrationClass.MAX_BYTES));}}
    private static int length(DataInputStream in,int max)throws IOException {int n=in.readInt();if(n<0||n>max)throw invalid("长度超过预算");return n;}
    private static int u2(byte[] code,int p)throws IOException {if(p+1>=code.length)throw invalid("指令被截断");return Byte.toUnsignedInt(code[p])*256+Byte.toUnsignedInt(code[p+1]);}
    private static IOException invalid(String message){return new IOException("注册类元信息："+message);}
}
