package com.mcworldexplorer.voxel.resource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Nearest-filtered static PNG atlas, with a one-pixel extruded border per tile. */
public final class TextureAtlas {
    public static final int MAX_IMAGE_SIZE = 1024;
    public static final int MAX_SIZE = 4096;
    public static final int MAX_TILES = 2048;
    private final int width, height, tileCount;
    private final int[] pixels;

    private TextureAtlas(int width, int height, int tileCount, int[] pixels) {
        this.width = width; this.height = height; this.tileCount = tileCount; this.pixels = pixels;
    }
    public int width() { return width; }
    public int height() { return height; }
    public int tileCount() { return tileCount; }
    public int[] pixels() { return pixels.clone(); }
    public long estimatedBytes() { return (long) pixels.length * Integer.BYTES; }
    public record Tile(int x, int y, int width, int height) { }

    public static BufferedImage decode(byte[] png) throws IOException {return decode(png,true);}
    public static BufferedImage decode(byte[] png,boolean animationStrip) throws IOException {
        byte[] signature = {(byte)137,80,78,71,13,10,26,10};
        if (png.length < 33 || !Arrays.equals(signature, Arrays.copyOf(png, 8)))
            throw new IOException("纹理不是有效 PNG");
        ImageReader reader = ImageIO.getImageReadersByFormatName("png").next();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            reader.setInput(input, true, true);
            int w = reader.getWidth(0), h = reader.getHeight(0);
            if (w < 1 || w > MAX_IMAGE_SIZE || h < 1 || (long)w*h>16_777_216 || (animationStrip ? h < w || h % w != 0 || h / w > 256 : h > MAX_IMAGE_SIZE))
                throw new IOException("纹理须为不超过 "+MAX_IMAGE_SIZE+" 像素的方形或有界纵向动画帧条");
            var parameters=reader.getDefaultReadParam();
            parameters.setSourceRegion(new java.awt.Rectangle(0,0,w,animationStrip?w:h));
            return spritePixels(reader.read(0,parameters)); // Static preview uses frame zero, not animation playback.
        } finally { reader.dispose(); }
    }

    /** Rectangular static sprites keep their aspect; animation dimensions come from metadata. */
    public static BufferedImage decodeResource(byte[] png,java.util.Optional<String> metadata) throws IOException {
        if(metadata.isEmpty())return decode(png,false);
        com.google.gson.JsonObject root;
        try(var json=new com.google.gson.stream.JsonReader(new java.io.StringReader(metadata.orElseThrow()))) {
            json.setStrictness(com.google.gson.Strictness.STRICT);json.setNestingLimit(16);
            root=com.google.gson.JsonParser.parseReader(json).getAsJsonObject();
            if(json.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT)throw new IOException("纹理元数据尾随内容");
        }catch(RuntimeException invalid){throw new IOException("纹理元数据无效",invalid);}
        if(!root.has("animation"))return decode(png,false);
        byte[] signature={(byte)137,80,78,71,13,10,26,10};
        if(png.length<33||!Arrays.equals(signature,Arrays.copyOf(png,8)))throw new IOException("纹理不是有效 PNG");
        ImageReader reader=ImageIO.getImageReadersByFormatName("png").next();
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
            if(width<1||height<1||width>65536||height>65536||(long)width*height>16_777_216)throw new IOException("动画画布超过解码预算");
            var animation=root.getAsJsonObject("animation");
            int fw=animation.has("width")?positive(animation.get("width")):animation.has("height")?width:Math.min(width,height);
            int fh=animation.has("height")?positive(animation.get("height")):animation.has("width")?height:Math.min(width,height);
            if(fw>MAX_IMAGE_SIZE||fh>MAX_IMAGE_SIZE||width%fw!=0||height%fh!=0)throw new IOException("动画帧尺寸无效或超过 "+MAX_IMAGE_SIZE+" 像素");
            if(animation.has("frametime"))positive(animation.get("frametime"));
            int frame=0;
            if(animation.has("frames")) {
                var frames=animation.getAsJsonArray("frames");if(frames.size()>1024)throw new IOException("动画帧列表超过 1024 项");
                for(int i=0;i<frames.size();i++) {
                    var item=frames.get(i);int index;
                    if(item.isJsonObject()){var entry=item.getAsJsonObject();index=nonnegative(entry.get("index"));if(entry.has("time"))positive(entry.get("time"));}
                    else index=nonnegative(item);
                    if(index>=(width/fw)*(height/fh))throw new IOException("动画帧索引越界");if(i==0)frame=index;
                }
            }
            var parameters=reader.getDefaultReadParam();parameters.setSourceRegion(new java.awt.Rectangle((frame%(width/fw))*fw,(frame/(width/fw))*fh,fw,fh));
            return spritePixels(reader.read(0,parameters));
        }catch(RuntimeException invalid){throw new IOException("动画元数据无效",invalid);}finally{reader.dispose();}
    }
    // World sprites use the stored gray byte in each RGB channel. Java's gray ColorSpace
    // instead performs a linear-to-sRGB conversion when getRGB is called on a gray image.
    private static BufferedImage spritePixels(BufferedImage image) {
        var model=image.getColorModel();
        if (model.getColorSpace().getType()!=java.awt.color.ColorSpace.TYPE_GRAY) return image;
        var raster=image.getRaster();
        var output=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_ARGB);
        int[] pixels=((java.awt.image.DataBufferInt)output.getRaster().getDataBuffer()).getData();
        int grayBits=raster.getSampleModel().getSampleSize(0);
        int alphaBits=model.hasAlpha()?raster.getSampleModel().getSampleSize(1):0;
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
            int gray=toByte(raster.getSample(x,y,0),grayBits);
            int alpha=alphaBits==0?255:toByte(raster.getSample(x,y,1),alphaBits);
            pixels[y*image.getWidth()+x]=(alpha<<24)|(gray*0x010101);
        }
        return output;
    }

    private static int toByte(int channel,int bits) {
        return bits>=8 ? channel >>> (bits-8) : channel*255/((1<<bits)-1);
    }

    private static int nonnegative(com.google.gson.JsonElement value)throws IOException {
        try{if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException();int result=value.getAsBigDecimal().intValueExact();if(result<0)throw new IllegalArgumentException();return result;}
        catch(RuntimeException invalid){throw new IOException("动画索引或尺寸必须为有界整数",invalid);}
    }
    private static int positive(com.google.gson.JsonElement value)throws IOException {int result=nonnegative(value);if(result==0)throw new IOException("动画尺寸和帧时间须为正数");return result;}

    public static final class Builder {
        private final int[][] rows = new int[MAX_SIZE][];
        private final Map<String, Tile> tiles = new LinkedHashMap<>();
        private int x, y, rowHeight;

        public Tile add(String key, BufferedImage image) throws IOException {
            Tile existing = tiles.get(key);
            if (existing != null) return existing;
            int w = image.getWidth(), h = image.getHeight();
            if (w < 1 || h < 1 || w > MAX_IMAGE_SIZE || h > MAX_IMAGE_SIZE || tiles.size() >= MAX_TILES)
                throw new IOException("纹理图集单项尺寸或数量超限");
            if (x + w + 2 > MAX_SIZE) { x = 0; y += rowHeight; rowHeight = 0; }
            if (y + h + 2 > MAX_SIZE) throw new IOException("纹理图集超过 " + MAX_SIZE + "×" + MAX_SIZE + " 容量");
            Tile tile = new Tile(x + 1, y + 1, w, h);
            int[] pixels=new int[w];
            for (int py=-1;py<=h;py++) {
                int iy=y+py+1;
                if (rows[iy]==null) rows[iy]=new int[MAX_SIZE];
                image.getRGB(0,Math.clamp(py,0,h-1),w,1,pixels,0,w);
                System.arraycopy(pixels,0,rows[iy],x+1,w);
                rows[iy][x]=pixels[0];
                rows[iy][x+w+1]=pixels[w-1];
            }
            tiles.put(key, tile); x += w + 2; rowHeight = Math.max(rowHeight, h + 2);
            return tile;
        }
        public TextureAtlas build() {
            int height = 1;
            while (height < y + rowHeight) height *= 2;
            int[] snapshot=new int[MAX_SIZE*height];
            for(int row=0;row<height;row++)
                if(rows[row]!=null) System.arraycopy(rows[row],0,snapshot,row*MAX_SIZE,MAX_SIZE);
            return new TextureAtlas(MAX_SIZE,height,tiles.size(),snapshot);
        }
    }
}
