package com.mcworldexplorer.voxel.resource;

import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.RegistrationClassFile.*;
import static com.mcworldexplorer.voxel.resource.ClientBlockSymbols.unsupported;

/** Literal registry and direct BlockItem constructor protocols only; never evaluates game methods. */
final class ClientBlockRegistration {
    record Block(ResourceLocation id,Member field,Optional<Member> constructor) { }
    final ClientBlockSymbols symbols;
    private final BlockResourceSource source;
    private final Map<String,RegistrationClassFile> files=new LinkedHashMap<>();
    private final Map<String,String> classHashes=new HashMap<>();
    private int classBytes;
    private final Map<ResourceLocation,Block> blocks=new HashMap<>();
    private final Map<ResourceLocation,Block> items=new HashMap<>();
    private final Map<Member,Block> fields=new HashMap<>();
    private final Set<ResourceLocation> unsupportedItems=new HashSet<>();
    private final Set<Member> metadataSetters=new HashSet<>();
    private final Set<String> provenance=new LinkedHashSet<>();
    ClientBlockRegistration(BlockResourceSource source,ClientBlockSymbols symbols)throws IOException,ModelRule.Failure {
        this.source=Objects.requireNonNull(source);this.symbols=Objects.requireNonNull(symbols);
        for(String kernel:symbols.kernels())file(kernel);
        for(var kernel:symbols.methodKernels().entrySet())if(!tailSignature(code(kernel.getKey())).equals(kernel.getValue()))throw unsupported("客户端注册辅助方法指纹不匹配："+kernel.getKey());
        verifyRegistrationHelpers();indexBlocks();indexItems();
    }
    Block block(ResourceLocation id)throws ModelRule.Failure {var result=blocks.get(id);if(result==null)throw unsupported("已选客户端没有已识别的方块注册："+id);return result;}
    Optional<Block> referencedBlock(Member field){return Optional.ofNullable(fields.get(field));}
    Optional<Block> recognizedItem(ResourceLocation id){return Optional.ofNullable(items.get(id));}
    boolean independentBlockField(Member field)throws IOException,ModelRule.Failure {
        if(fields.containsKey(field))return true;
        var s=symbols;var span=new ArrayList<Instruction>();
        for(var i:code(new Member(s.type("Blocks"),"<clinit>","()V"))){
            span.add(i);if(i.opcode()!=179)continue;
            if(i.operand().equals(field)){
                // A ResourceKey registration can have an unknown text ID while still provably
                // constructing a distinct block. Retain field identity instead of inventing an ID.
                if(span.size()<6||span.getFirst().opcode()!=178||!(span.getFirst().operand() instanceof Member key)||!key.descriptor().equals(s.descriptor("ResourceKey"))
                        ||span.get(1).opcode()!=187||span.get(2).opcode()!=89||span.get(span.size()-3).opcode()!=183||!member(span,span.size()-3).owner().equals(((Type)span.get(1).operand()).internalName()))return false;
                var helper=s.method("Blocks.register","("+s.descriptor("ResourceKey")+s.descriptor("Block")+")"+s.descriptor("Block"));
                if(span.get(span.size()-2).opcode()!=184||!span.get(span.size()-2).operand().equals(helper))return false;
                var body=code(helper);requireOps(body,178,42,43,184,192,176);
                return body.getFirst().operand().equals(s.field("BuiltInRegistries.BLOCK"))&&s.call(member(body,3),"Registry.register")&&body.get(4).operand().equals(new Type(s.type("Block")));
            }span.clear();
        }return false;
    }
    Block item(ResourceLocation id)throws ModelRule.Failure {
        if(unsupportedItems.contains(id))throw unsupported("物品的BlockItem构造协议未识别："+id);
        var result=items.get(id);if(result==null)throw unsupported("已选客户端没有已识别的BlockItem绑定："+id);return result;
    }
    RegistrationClassFile file(String name)throws IOException,ModelRule.Failure {
        var cached=files.get(name);if(cached!=null)return cached;
        if(files.size()>=256)throw new ModelRule.Failure(BlockModelResolver.Reason.LIMIT_EXCEEDED,"方块注册协议超过256个类");
        var data=source.clientClass(name).orElseThrow(()->unsupported("已选客户端缺少方块协议类："+name));
        classBytes=Math.addExact(classBytes,data.bytes().length);if(classBytes>8*1024*1024)throw new ModelRule.Failure(BlockModelResolver.Reason.LIMIT_EXCEEDED,"方块注册结构超过8MiB");
        symbols.verifyKernel(data);var parsed=RegistrationClassFile.read(data);files.put(name,parsed);
        try{classHashes.put(name,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data.bytes())));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        provenance.add(data.origin()+"；来源指纹 "+data.sourceFingerprint());return parsed;
    }
    void verifyClassProof(String name,String expected)throws IOException,ModelRule.Failure {file(name);if(!expected.equals(classHashes.get(name)))throw unsupported("客户端构造缓存协议指纹不匹配："+name);}
    List<String> notes(){return provenance.stream().map(origin->"客户端方块注册与默认状态："+origin+"；已读取"+files.size()+"个受限协议类，未加载或执行类").toList();}
    List<Instruction> code(Member method)throws IOException,ModelRule.Failure {
        var owner=file(method.owner());var candidates=owner.methods().stream().filter(m->m.name().equals(method.name())&&m.descriptor().equals(method.descriptor())).toList();
        if(candidates.size()!=1)throw unsupported("方块协议方法缺失或不唯一："+method.owner()+"."+method.name());return owner.instructionsWithThrows(candidates.getFirst());
    }
    Member declaringField(Member reference)throws IOException,ModelRule.Failure {
        return declaringField(reference,reference.owner(),new HashMap<>(),new HashSet<>()).orElseThrow(()->unsupported("属性字段缺失："+reference));
    }
    /** JVMS 5.4.3.2: own declaration, superinterfaces, then superclass. Conflicting
     * interface declarations remain unsupported instead of selecting an arbitrary property. */
    private Optional<Member> declaringField(Member reference,String owner,Map<String,Optional<Member>> memo,Set<String> active)throws IOException,ModelRule.Failure {
        if(owner.isEmpty()||owner.equals("java/lang/Object"))return Optional.empty();
        if(memo.containsKey(owner))return memo.get(owner);
        if(!active.add(owner))throw unsupported("属性字段继承循环："+reference);
        if(active.size()+memo.size()>16)throw unsupported("属性字段继承超过16个类型："+reference);
        try{
            var definition=file(owner);Optional<Member> result;
            if(definition.declaresField(reference.name(),reference.descriptor()))result=Optional.of(new Member(owner,reference.name(),reference.descriptor()));
            else{
                var matches=new HashSet<Member>();
                for(String parent:definition.interfaces())declaringField(reference,parent,memo,active).ifPresent(matches::add);
                if(matches.size()>1)throw unsupported("属性字段存在多个接口声明："+reference);
                result=matches.isEmpty()?declaringField(reference,definition.parent(),memo,active):Optional.of(matches.iterator().next());
            }
            memo.put(owner,result);return result;
        }finally{active.remove(owner);}
    }
    boolean inheritedCall(Member reference,String alias)throws IOException,ModelRule.Failure {
        if(!symbols.signature(reference,alias))return false;return inheritedCall(reference,alias,new HashSet<>());
    }
    private boolean inheritedCall(Member reference,String alias,Set<String> seen)throws IOException,ModelRule.Failure {
        if(symbols.call(reference,alias))return true;if(reference.owner().startsWith("java/")||!seen.add(reference.owner())||seen.size()>16)return false;
        var owner=file(reference.owner());if(owner.methods().stream().anyMatch(m->m.name().equals(reference.name())&&m.descriptor().equals(reference.descriptor())&&(m.access()&0x0400)==0))return false;
        if(!owner.parent().isEmpty()&&inheritedCall(new Member(owner.parent(),reference.name(),reference.descriptor()),alias,seen))return true;
        for(String parent:owner.interfaces())if(inheritedCall(new Member(parent,reference.name(),reference.descriptor()),alias,seen))return true;return false;
    }
    List<Instruction> initializer(Member reference)throws IOException,ModelRule.Failure {
        var field=declaringField(reference);var owner=file(field.owner());
        var methods=owner.methods().stream().filter(m->m.name().equals("<clinit>")&&m.descriptor().equals("()V")).toList();
        if(methods.size()!=1)throw unsupported("属性常量初始化器缺失或重复");
        var code=owner.unconditionalTail(methods.getFirst());int start=0;List<Instruction> result=null;
        for(int i=0;i<code.size();i++)if(code.get(i).opcode()==179){if(code.get(i).operand().equals(field)){if(result!=null)throw unsupported("属性常量重复写入");result=List.copyOf(code.subList(start,i));}start=i+1;}
        if(result==null)throw unsupported("属性字段没有确定的常量赋值："+field);return result;
    }
    private void verifyRegistrationHelpers()throws IOException,ModelRule.Failure {
        var s=symbols;String block=s.descriptor("Block"),item=s.descriptor("Item"),blockItem=s.descriptor("BlockItem"),props=s.descriptor("Item$Properties");
        var register=s.method("Blocks.register","(Ljava/lang/String;"+block+")"+block);var c=code(register);
        requireOps(c,178,42,43,184,192,176);
        if(!c.getFirst().operand().equals(s.field("BuiltInRegistries.BLOCK"))||!s.call(member(c,3),"Registry.register")||!c.get(4).operand().equals(new Type(s.type("Block"))))throw unsupported("方块注册没有使用已识别的方块注册表");
        c=code(s.method("Items.registerBlock","("+block+")"+item));requireOps(c,187,89,42,187,89,183,183,184,176);
        if(!c.get(0).operand().equals(new Type(s.type("BlockItem")))||!c.get(3).operand().equals(new Type(s.type("Item$Properties")))
                ||!member(c,5).equals(new Member(s.type("Item$Properties"),"<init>","()V"))||!member(c,6).equals(new Member(s.type("BlockItem"),"<init>","("+block+props+")V"))
                ||!member(c,7).equals(s.method("Items.registerBlock","("+blockItem+")"+item)))throw unsupported("默认BlockItem构造协议不同");
        c=code(s.method("Items.registerBlock","("+blockItem+")"+item));requireOps(c,42,182,42,184,176);
        if(!s.call(member(c,1),"BlockItem.getBlock")||!member(c,3).equals(s.method("Items.registerBlock","("+block+item+")"+item)))throw unsupported("BlockItem注册没有取得其实际方块");
        c=code(s.method("Items.registerBlock","("+block+item+")"+item));requireOps(c,178,42,185,43,184,176);
        if(!c.get(0).operand().equals(s.field("BuiltInRegistries.BLOCK"))||!inheritedCall(member(c,2),"Registry.getKey")||!s.call(member(c,4),"Items.registerItem"))throw unsupported("BlockItem注册ID不是方块注册键");
        c=code(s.method("BlockItem.getBlock","()"+block));requireOps(c,42,180,176);
        if(!c.get(1).operand().equals(s.field("BlockItem.block")))throw unsupported("BlockItem方块引用不是确定字段");
        verifyItemConstructor(s.type("BlockItem"),new HashSet<>());
    }
    private void indexBlocks()throws IOException,ModelRule.Failure {
        var span=new ArrayList<Instruction>();var s=symbols;var seen=new HashSet<Member>();var initializer=code(new Member(s.type("Blocks"),"<clinit>","()V"));linearRegistry("Blocks",initializer);
        for(var ins:initializer){
            span.add(ins);if(ins.opcode()!=179)continue;var field=(Member)ins.operand();
            if(!seen.add(field))throw unsupported("客户端方块注册字段重复写入");
            if(field.owner().equals(s.type("Blocks"))&&field.descriptor().equals(s.descriptor("Block"))&&span.size()>3&&span.getFirst().operand() instanceof String name){
                var terminal=span.get(span.size()-2);
                if(terminal.opcode()==184&&terminal.operand() instanceof Member call&&s.call(call,"Blocks.register")){
                    if(span.stream().anyMatch(i->i.operand() instanceof Branch||i.opcode()==191))throw unsupported("方块注册含条件控制流");
                    ResourceLocation id;try{id=ResourceLocation.parse(name);}catch(IllegalArgumentException invalid){throw unsupported("方块注册ID无效");}
                    Optional<Member> constructor=Optional.empty();
                    if(span.get(1).opcode()==187&&span.get(1).operand() instanceof Type constructed&&span.get(2).opcode()==89){
                        var last=span.get(span.size()-3);if(last.opcode()==183&&last.operand() instanceof Member ctor&&ctor.name().equals("<init>")&&ctor.owner().equals(constructed.internalName()))constructor=Optional.of(ctor);
                    }
                    var block=new Block(id,field,constructor);if(blocks.putIfAbsent(id,block)!=null||fields.putIfAbsent(field,block)!=null)throw unsupported("方块注册ID或字段重复");
                    if(blocks.size()>4096)throw new ModelRule.Failure(BlockModelResolver.Reason.LIMIT_EXCEEDED,"客户端方块注册超过4096项");
                }
            }span.clear();
        }
    }
    private void indexItems()throws IOException,ModelRule.Failure {
        var span=new ArrayList<Instruction>();var s=symbols;var seen=new HashSet<Member>();var initializer=code(new Member(s.type("Items"),"<clinit>","()V"));linearRegistry("Items",initializer);
        for(var ins:initializer){
            span.add(ins);if(ins.opcode()!=179)continue;
            try{
                var target=member(span,span.size()-1);if(!target.owner().equals(s.type("Items"))||!target.descriptor().equals(s.descriptor("Item")))continue;
                if(!seen.add(target))throw unsupported("客户端物品注册字段重复写入");
                if(span.getFirst().operand() instanceof String text){ResourceLocation explicit;try{explicit=ResourceLocation.parse(text);}catch(IllegalArgumentException invalid){throw unsupported("物品注册ID无效");}if(items.containsKey(explicit)||!unsupportedItems.add(explicit))throw unsupported("物品注册ID重复："+explicit);}
                if(span.size()==3&&span.getFirst().opcode()==178&&span.get(1).opcode()==184&&span.get(1).operand().equals(s.method("Items.registerBlock","("+s.descriptor("Block")+")"+s.descriptor("Item")))){
                    var block=fields.get(span.getFirst().operand());if(block!=null)putItem(block.id(),block);
                }else indexConstructedItem(span);
                if(items.size()+unsupportedItems.size()>4096)throw new ModelRule.Failure(BlockModelResolver.Reason.LIMIT_EXCEEDED,"客户端物品注册索引超过4096项");
            }finally{span.clear();}
        }
    }
    private void indexConstructedItem(List<Instruction> span)throws IOException,ModelRule.Failure {
        if(span.size()<9)return;var s=symbols;int start=span.getFirst().operand() instanceof String?1:0;
        if(span.get(start).opcode()!=187||span.get(start+1).opcode()!=89||span.get(start+2).opcode()!=178)return;
        var block=fields.get(span.get(start+2).operand());if(block==null)return;
        var end=span.get(span.size()-2);if(end.opcode()!=184||!(end.operand() instanceof Member register))return;
        String item=s.descriptor("Item");boolean explicit=start==1;
        if(explicit?!register.equals(s.method("Items.registerItem","(Ljava/lang/String;"+item+")"+item))
                :!register.equals(s.method("Items.registerBlock","("+s.descriptor("BlockItem")+")"+item)))return;
        ResourceLocation id;try{id=explicit?ResourceLocation.parse((String)span.getFirst().operand()):block.id();}catch(IllegalArgumentException invalid){throw unsupported("物品注册ID无效");}
        String owner=((Type)span.get(start).operand()).internalName();
        // The block argument stays on the stack while a fresh Properties object is decorated.
        // Only reference-value metadata setters are accepted; arbitrary factories remain unknown.
        if(span.size()<start+9||span.get(start+3).opcode()!=187||!span.get(start+3).operand().equals(new Type(s.type("Item$Properties")))
                ||span.get(start+4).opcode()!=89||span.get(start+5).opcode()!=183||!span.get(start+5).operand().equals(new Member(s.type("Item$Properties"),"<init>","()V"))
                ||span.get(span.size()-3).opcode()!=183||!span.get(span.size()-3).operand().equals(new Member(owner,"<init>","("+s.descriptor("Block")+s.descriptor("Item$Properties")+")V"))){unsupportedItems.add(id);return;}
        try{verifyMetadata(span.subList(start+6,span.size()-3));verifyItemConstructor(owner,new HashSet<>());}catch(ModelRule.Failure invalid){if(invalid.reason()==BlockModelResolver.Reason.LIMIT_EXCEEDED)throw invalid;unsupportedItems.add(id);return;}
        putItem(id,block);
    }
    private void verifyMetadata(List<Instruction> chain)throws IOException,ModelRule.Failure {
        if(chain.size()>32)throw new ModelRule.Failure(BlockModelResolver.Reason.LIMIT_EXCEEDED,"物品元数据赋值超过16项");
        if(chain.size()%2!=0)throw unsupported("物品元数据不是静态引用赋值链");
        String owner=symbols.type("Item$Properties");
        for(int p=0;p<chain.size();p+=2){
            if(chain.get(p).opcode()!=178||chain.get(p+1).opcode()!=182)throw unsupported("物品元数据含未知参数或调用");
            var field=member(chain,p);var method=member(chain,p+1);
            if(!field.descriptor().startsWith("L")||!field.descriptor().endsWith(";")||!method.owner().equals(owner)||!method.descriptor().equals("("+field.descriptor()+")L"+owner+";"))throw unsupported("物品元数据没有原样返回属性对象");
            if(metadataSetters.contains(method))continue;
            var definition=file(owner);var candidates=definition.methods().stream().filter(m->m.name().equals(method.name())&&m.descriptor().equals(method.descriptor())).toList();
            if(candidates.size()!=1||(candidates.getFirst().access()&0x0508)!=0)throw unsupported("物品元数据方法缺失或不是具体实例方法");
            var body=code(method);boolean direct=body.size()==5&&body.get(0).opcode()==42&&body.get(1).opcode()==43&&body.get(2).opcode()==181&&body.get(3).opcode()==42&&body.get(4).opcode()==176;
            if(direct){var target=member(body,2);if(!target.owner().equals(owner)||!target.descriptor().equals(field.descriptor())||!definition.declaresField(target.name(),target.descriptor()))throw unsupported("物品元数据写入了外部对象");}
            else{
                // Component-backed setters have reviewed dependency proofs in the version profile.
                // These do not contain item IDs, food constants, or a block-to-item lookup table.
                var proofs=symbols.itemMetadataProof(method).orElseThrow(()->unsupported("物品元数据辅助协议未核对"));
                for(var proof:proofs.entrySet())verifyClassProof(proof.getKey(),proof.getValue());
            }
            metadataSetters.add(method);
        }
    }
    private void putItem(ResourceLocation id,Block block)throws ModelRule.Failure {
        if(items.putIfAbsent(id,block)!=null)throw unsupported("BlockItem注册ID重复："+id);
        unsupportedItems.remove(id);
        if(items.size()>4096)throw new ModelRule.Failure(BlockModelResolver.Reason.LIMIT_EXCEEDED,"客户端BlockItem注册超过4096项");
    }
    private void verifyItemConstructor(String owner,Set<String> chain)throws IOException,ModelRule.Failure {
        var s=symbols;if(!chain.add(owner)||chain.size()>16)throw unsupported("BlockItem构造继承循环或过深");
        var definition=file(owner);String descriptor="("+s.descriptor("Block")+s.descriptor("Item$Properties")+")V";var c=code(new Member(owner,"<init>",descriptor));
        if(owner.equals(s.type("BlockItem"))){
            requireOps(c,42,44,183,42,43,181,177);
            if(!member(c,2).equals(new Member(s.type("Item"),"<init>","("+s.descriptor("Item$Properties")+")V"))||!c.get(5).operand().equals(s.field("BlockItem.block")))throw unsupported("BlockItem没有直接保存构造方块引用");
        }else{
            if(definition.methods().stream().anyMatch(m->s.signature(new Member(owner,m.name(),m.descriptor()),"BlockItem.getBlock")))throw unsupported("BlockItem子类重写了方块引用");
            requireOps(c,42,43,44,183,177);if(!member(c,3).equals(new Member(definition.parent(),"<init>",descriptor)))throw unsupported("BlockItem子类没有原样传递方块参数");verifyItemConstructor(definition.parent(),chain);
        }
    }
    static Member member(List<Instruction> code,int index)throws ModelRule.Failure {if(!(code.get(index).operand() instanceof Member value))throw unsupported("注册协议成员类型不符");return value;}
    static void requireOps(List<Instruction> code,int... expected)throws ModelRule.Failure {if(code.size()!=expected.length)throw unsupported("注册方法含额外步骤");for(int i=0;i<expected.length;i++)if(code.get(i).opcode()!=expected[i])throw unsupported("注册方法结构不符："+i);}
    private void linearRegistry(String owner,List<Instruction> code)throws ModelRule.Failure {
        int last=-1;for(int i=0;i<code.size();i++)if(code.get(i).opcode()==179)last=i;
        if(last<0||code.getLast().opcode()!=177||code.subList(0,last+1).stream().anyMatch(i->i.operand() instanceof Branch||i.opcode()==191||i.opcode()>=172&&i.opcode()<=177))throw unsupported("客户端注册入口不是无分支的确定初始化");
        var tail=code.subList(last+1,code.size());
        if(tail.size()!=1&&!symbols.registryTail(owner,tailSignature(tail)))throw unsupported("注册后段不是已核对的状态缓存初始化");
        if(tail.stream().anyMatch(i->i.operand() instanceof Branch branch&&branch.target()<tail.getFirst().offset()))throw unsupported("注册后段跳回注册区");
    }
    static String tailSignature(List<Instruction> code){
        StringBuilder text=new StringBuilder();int base=code.getFirst().offset();for(var i:code)text.append(i.offset()-base).append(':').append(i.opcode()).append(':').append(i.operand() instanceof Branch b?"branch:"+(b.target()-base):String.valueOf(i.operand())).append('\n');
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
