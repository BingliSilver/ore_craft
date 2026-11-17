"""生成有体积感的组件图标、原生导弹物品模型及静态预览，不启动游戏。"""

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
TEXTURES = ROOT / 'src/main/resources/assets/ore_craft/textures/item'
MODELS = ROOT / 'src/main/resources/assets/ore_craft/models/item'
ARTWORK = ROOT / 'artwork'
# 共享色板把装甲、铜制连接件、末影晶体与能量窗统一为同一系列。
COLORS = ['#202735', '#48576b', '#8298ac', '#c3d5df', '#edf8f4', '#694335',
          '#bb794a', '#f4c780', '#741f32', '#cc3f4b', '#ff8a67', '#164d58',
          '#22a7ad', '#9bfff1', '#624496', '#b58aeb']
NAMES = ['reinforced_missile_casing', 'spatial_guidance_chip',
         'mineral_propulsion_engine', 'mineral_high_explosive_warhead',
         'spatial_stabilizer', 'mineral_spatial_guided_missile']
LABELS = ['强化弹体外壳', '空间制导芯片', '矿质推进引擎',
          '矿质高爆战斗部', '空间稳定器', '矿质空间制导导弹']


def box(parts, start, end, color):
    """记录立方体及材质；渲染预览和游戏模型使用同一份几何数据。"""
    parts.append((tuple(start), tuple(end), color))


def collar(parts, y, radius, color, height=0.65):
    """交叠的两个矩形截面构成削角环带，减少普通方柱的生硬感。"""
    box(parts, (8-radius, y, 8-radius+0.65), (8+radius, y+height, 8+radius-0.65), color)
    box(parts, (8-radius+0.65, y, 8-radius), (8+radius-0.65, y+height, 8+radius), color)


def create_parts():
    """为六种物品设计各自的轮廓；几何仅用于显示，不携带游戏功能。"""
    result = []
    parts = []
    # 装甲外壳由四片有厚度的护板构成，上端留出真实空腔。
    for a, b in [((4,3,4),(5.2,12,12)), ((10.8,3,4),(12,12,12)),
                 ((5.2,3,4),(10.8,12,5.2)), ((5.2,3,10.8),(10.8,12,12))]:
        box(parts, a, b, 2)
    for y in (3, 10.7):
        for x in (3.7, 11):
            box(parts, (x,y,3.7), (x+1.3,y+1,12.3), 3)
        for z in (3.7, 11):
            box(parts, (5,y,z), (11,y+1,z+1.3), 3)
    box(parts, (5.6,4.5,3.6), (10.4,10.4,4), 0)
    box(parts, (6,5,3.4), (6.5,10,3.6), 14)
    box(parts, (7,5,3.4), (7.4,10,3.6), 15)
    box(parts, (5.5,6.7,3.2), (10.5,7.6,3.6), 6)
    for x in (4.2,11.2):
        for y in (4,10.8):
            box(parts, (x,y,3.4), (x+0.6,y+0.6,3.8), 7)
    result.append(parts)
    # 芯片用薄基板、金色引脚与凸起晶体组成，保留可读的电路线。
    parts = []
    box(parts, (3,3,7), (13,13,8), 0)
    box(parts, (3.5,3.5,6.6), (12.5,12.5,7), 11)
    for t in (4,6,8,10,12):
        box(parts, (t,2,7), (t+0.6,3.6,7.7), 7)
        box(parts, (t,12.4,7), (t+0.6,14,7.7), 7)
        box(parts, (2,t,7), (3.6,t+0.6,7.7), 6)
        box(parts, (12.4,t,7), (14,t+0.6,7.7), 6)
    for t in (4.5,10.8):
        box(parts, (t,4,6.4), (t+0.35,12,6.6), 12)
        box(parts, (4,t,6.4), (12,t+0.35,6.6), 12)
    box(parts, (5.3,5.3,5.8), (10.7,10.7,6.6), 6)
    box(parts, (5.8,5.8,5.2), (10.2,10.2,5.8), 14)
    box(parts, (6.5,6.5,4.5), (9.5,9.5,5.2), 12)
    box(parts, (7,7.8,4.3), (8.8,9,4.5), 13)
    box(parts, (4,4,6.2), (5,4.8,6.6), 9)
    result.append(parts)
    # 推进引擎包含散热环、铜管和凹入的喷口，取消图标上的平面火焰。
    parts = []
    collar(parts, 5, 2.8, 1, 6)
    for y in (5,6.2,7.4,10.7):
        collar(parts, y, 3.4, 2, 0.65)
    collar(parts, 11.4, 2.7, 3, 0.8)
    for x in (4.4,10.8):
        box(parts, (x,7,6.8), (x+0.8,11,8.5), 6)
    box(parts, (7,8.4,4.4), (9,10.6,5.3), 0)
    box(parts, (7.4,8.8,4.2), (8.6,10.2,4.4), 13)
    for x in (4.8,10.2):
        box(parts, (x,2.5,5), (x+1,5,11), 6)
    for z in (5,10):
        box(parts, (5.8,2.5,z), (10.2,5,z+1), 6)
    box(parts, (6,4.3,6), (10,4.8,10), 0)
    box(parts, (7,4.1,7), (9,4.3,9), 10)
    result.append(parts)
    # 战斗部为短粗的多级弹头，底座与引擎明显不同。
    parts = []
    collar(parts, 2.5, 3.7, 1, 3)
    collar(parts, 2.5, 3.9, 6)
    collar(parts, 5, 3.9, 3)
    for y, radius in ((5.7,3.5),(7.5,3),(9.2,2.35),(10.8,1.65),(12.1,0.9)):
        collar(parts, y, radius, 9 if y < 10 else 8, 1.8 if y<9 else 1.4)
    box(parts, (6.6,3.4,3.9), (9.4,4.7,4.3), 0)
    box(parts, (7,3.7,3.7), (9,4.3,3.9), 13)
    result.append(parts)
    # 空间稳定器用八段机械环包围悬浮晶核，四角的紫晶形成识别特征。
    parts = []
    for a,b in [((5,2.5,7),(11,4,8.4)),((5,12,7),(11,13.5,8.4)),
                ((2.5,5,7),(4,11,8.4)),((12,5,7),(13.5,11,8.4)),
                ((3.5,3.5,7),(5.5,5.5,8.4)),((10.5,3.5,7),(12.5,5.5,8.4)),
                ((3.5,10.5,7),(5.5,12.5,8.4)),((10.5,10.5,7),(12.5,12.5,8.4))]:
        box(parts,a,b,2)
    for x,y in ((3.5,3.5),(10.5,3.5),(3.5,10.5),(10.5,10.5)):
        box(parts,(x,y,6.3),(x+2,y+2,7),14)
        box(parts,(x+0.5,y+0.5,5.6),(x+1.5,y+1.5,6.3),15)
    box(parts,(6.2,6.2,6),(9.8,9.8,8.5),11)
    box(parts,(6.8,6.8,5.4),(9.2,9.2,8.8),12)
    box(parts,(7.3,7.3,5),(8.7,8.7,5.4),13)
    for a,b in [((7.6,4,7),(8.4,6.2,7.5)),((7.6,9.8,7),(8.4,12,7.5)),
                ((4,7.6,7),(6.2,8.4,7.5)),((9.8,7.6,7),(12,8.4,7.5))]:
        box(parts,a,b,12)
    result.append(parts)
    # 导弹实际使用这些原生 elements：长弹体、阶梯收尖弹头、四向尾翼和内凹喷口。
    parts = []
    collar(parts, 2.8, 1.65, 2, 8.7)
    box(parts,(6.9,3.3,6.3),(7.35,10.6,6.36),3)
    collar(parts, 3, 1.8, 1, 0.55)
    collar(parts, 10.8, 1.85, 6, 0.65)
    for y,r in ((11.45,1.65),(12.2,1.4),(12.95,1.12),(13.7,0.8)):
        # 最末一级半径小于削角宽度时使用单个方块，避免无效几何。
        collar(parts,y,r,9,0.75)
    box(parts,(7.55,14.45,7.55),(8.45,15.1,8.45),8)
    for z in (6.2,9.65):
        box(parts,(7.4,7,z),(8.6,9.8,z+0.15),0)
        box(parts,(7.7,7.35,z-0.02),(8.3,9.45,z+0.17),12)
        box(parts,(7.8,8.7,z-0.03),(8.2,9.35,z+0.18),13)
    # 分段尾翼形成后掠轮廓；宽度向喷口逐渐增大，四个方向均有实体厚度。
    for y, reach in ((2.8,3.8),(3.7,3.1),(4.6,2.4)):
        for sign in (-1,1):
            lo,hi = sorted((8+sign*1.3,8+sign*reach))
            box(parts,(lo,y,7.75),(hi,y+0.9,8.25),1)
            box(parts,(7.75,y,lo),(8.25,y+0.9,hi),1)
    for x,z in ((4.2,7.7),(11,7.7),(7.7,4.2),(7.7,11)):
        box(parts,(x,2.8,z),(x+0.8,3.4,z+0.6),6)
    collar(parts, 1.9, 1.35, 5, 0.9)
    for x in (6.6,8.8):
        box(parts,(x,1.4,6.6),(x+0.6,2,9.4),2)
    for z in (6.6,8.8):
        box(parts,(7.2,1.4,z),(8.8,2,z+0.6),2)
    box(parts,(7.2,1.85,7.2),(8.8,1.95,8.8),0)
    result.append(parts)
    return result


def rotate(point, angles):
    """围绕物品中心依次应用 Y、X、Z 旋转，用于静态正交预览。"""
    x,y,z = (v-8 for v in point)
    ax,ay,az = map(math.radians, angles)
    x,z = x*math.cos(ay)+z*math.sin(ay), -x*math.sin(ay)+z*math.cos(ay)
    y,z = y*math.cos(ax)-z*math.sin(ax), y*math.sin(ax)+z*math.cos(ax)
    x,y = x*math.cos(az)-y*math.sin(az), x*math.sin(az)+y*math.cos(az)
    return x,y,z


def render(parts, angles, size=64):
    """以深度缓冲绘制可见表面，正确处理空腔与交叠构件，保留清晰像素边缘。"""
    faces = []
    for a,b,color in parts:
        x,y,z = a
        X,Y,Z = b
        vertices = [(x,y,z),(X,y,z),(X,Y,z),(x,Y,z),(x,y,Z),(X,y,Z),(X,Y,Z),(x,Y,Z)]
        points = [rotate(p,angles) for p in vertices]
        for ids,light in [((0,1,2,3),0.92),((5,4,7,6),0.70),((4,0,3,7),0.78),
                          ((1,5,6,2),0.66),((3,2,6,7),1.12),((4,5,1,0),0.52)]:
            faces.append(([points[i] for i in ids],color,light))
    all_points = [p for face,_,_ in faces for p in face]
    low = [min(p[i] for p in all_points) for i in (0,1)]
    high = [max(p[i] for p in all_points) for i in (0,1)]
    scale = (size-8)/max(high[i]-low[i] for i in (0,1))
    center = [(low[i]+high[i])/2 for i in (0,1)]
    image = Image.new('RGBA',(size,size))
    pixels = image.load()
    depth = [[float('inf')]*size for _ in range(size)]
    for face,color,light in faces:
        points = [((p[0]-center[0])*scale+size/2, size/2-(p[1]-center[1])*scale,p[2]) for p in face]
        rgb = tuple(int(COLORS[color][i:i+2],16) for i in (1,3,5))
        for ids in ((0,1,2),(0,2,3)):
            a,b,c = [points[i] for i in ids]
            den = (b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
            if abs(den)<1e-8:
                continue
            for py in range(max(0,int(min(p[1] for p in (a,b,c)))),min(size,math.ceil(max(p[1] for p in (a,b,c)))+1)):
                for px in range(max(0,int(min(p[0] for p in (a,b,c)))),min(size,math.ceil(max(p[0] for p in (a,b,c)))+1)):
                    u=((b[1]-c[1])*(px+0.5-c[0])+(c[0]-b[0])*(py+0.5-c[1]))/den
                    v=((c[1]-a[1])*(px+0.5-c[0])+(a[0]-c[0])*(py+0.5-c[1]))/den
                    w=1-u-v
                    if min(u,v,w)<-1e-7:
                        continue
                    d=u*a[2]+v*b[2]+w*c[2]
                    if d<depth[py][px]:
                        depth[py][px]=d
                        pixels[px,py]=tuple(min(255,int(value*light)) for value in rgb)+(255,)
    return image


def save_model(parts):
    """导出原版 JSON 模型及色板图集，适配 GUI、双手、掉落和物品展示框。"""
    atlas=Image.new('RGBA',(32,32))
    draw=ImageDraw.Draw(atlas)
    for index,color in enumerate(COLORS):
        x,y=(index%4)*8,(index//4)*8
        draw.rectangle((x,y,x+7,y+7),fill=color)
    atlas.save(TEXTURES/'mineral_spatial_guided_missile_atlas.png')
    elements=[]
    for a,b,color in parts:
        # 色块中央采样避免图集相邻材质在缩小后串色。
        u,v=(color%4)*4+1,(color//4)*4+1
        elements.append({'from':a,'to':b,'faces':{face:{'uv':[u,v,u+2,v+2],'texture':'#body'}
                                                 for face in ('north','south','east','west','up','down')}})
    def display(rotation,translation,scale):
        return {'rotation':rotation,'translation':translation,'scale':[scale]*3}
    model={'credit':'ore_craft: generated by artwork/generate_missile_models.py',
           'gui_light':'side','ambientocclusion':False,
           'textures':{'body':'ore_craft:item/mineral_spatial_guided_missile_atlas',
                       'particle':'ore_craft:item/mineral_spatial_guided_missile_atlas'},
           'elements':elements,
           'display':{'gui':display([20,-35,-35],[0,0,0],1),
                      'ground':display([0,0,0],[0,2,0],0.6),
                      'fixed':display([0,180,-35],[0,0,0],0.85),
                      'thirdperson_righthand':display([0,0,0],[0,2,1],0.8),
                      'thirdperson_lefthand':display([0,0,0],[0,2,1],0.8),
                      'firstperson_righthand':display([0,-20,-20],[1,1,0],0.8),
                      'firstperson_lefthand':display([0,20,20],[1,1,0],0.8)}}
    (MODELS/'mineral_spatial_guided_missile.json').write_text(json.dumps(model,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


def main():
    """保存可重复生成的资源和中英文清晰的预览，用于免启动游戏的外观审查。"""
    parts=create_parts()
    sprites=[]
    for index,geometry in enumerate(parts):
        angles=(18,-28,-15) if index<5 else (20,-35,-35)
        sprite=render(geometry,angles)
        sprite.save(TEXTURES/(NAMES[index]+'.png'))
        sprites.append(sprite)
    save_model(parts[-1])
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',21)
    small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    preview=Image.new('RGB',(1080,700),'#121e2a')
    draw=ImageDraw.Draw(preview)
    draw.text((32,20),'矿质空间制导系统 / 物品外观',font=font,fill='#dce8ec')
    for i,sprite in enumerate(sprites):
        x,y=(i%3)*360,60+(i//3)*320
        draw.rounded_rectangle((x+16,y,x+344,y+300),radius=12,fill='#1d2c3a',outline='#364958')
        preview.paste(sprite.resize((224,224),Image.Resampling.NEAREST),(x+68,y+8),sprite.resize((224,224),Image.Resampling.NEAREST))
        draw.text((x+180,y+245),LABELS[i],font=font,fill='#e3eef2',anchor='mm')
        draw.text((x+180,y+277),'原生 3D 模型' if i==5 else '64 × 64 透明像素图标',font=small,fill='#79b9c0',anchor='mm')
    preview.save(ARTWORK/'missile_items_preview.png')
    views=Image.new('RGB',(1000,420),'#182634')
    draw=ImageDraw.Draw(views)
    for i,(angle,label) in enumerate([((20,-35,-35),'物品栏视角'),((12,-45,0),'侧面结构'),((-20,135,0),'背面与喷口')]):
        sprite=render(parts[-1],angle,128).resize((320,320),Image.Resampling.NEAREST)
        views.paste(sprite,(i*330+5,20),sprite)
        draw.text((i*330+165,370),label,font=font,fill='#e3eef2',anchor='mm')
    views.save(ARTWORK/'missile_model_preview.png')
    print('Generated six icons, missile atlas/model, and two static previews.')


if __name__=='__main__':
    main()
