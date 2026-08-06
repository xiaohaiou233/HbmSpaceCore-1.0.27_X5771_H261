package com.hbm.render.entity;

import org.lwjgl.opengl.GL11;

import com.hbm.main.ResourceManager;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

// ==== [modpack] 降落仓占位渲染：drop pod 贴图 + 0.8 立方体，渲染模型后置（spec 待定）
public class RenderLootPod extends Render {

	@Override
	public void doRender(Entity entity, double x, double y, double z, float f, float interp) {
		GL11.glPushMatrix();
		GL11.glTranslated(x, y, z);
		bindEntityTexture(entity);

		Tessellator tess = Tessellator.instance;
		float s = 0.4F;
		tess.startDrawingQuads();
		// bottom
		tess.addVertexWithUV(-s, 0, -s, 0, 0); tess.addVertexWithUV(-s, 0, s, 0, 1); tess.addVertexWithUV(s, 0, s, 1, 1); tess.addVertexWithUV(s, 0, -s, 1, 0);
		// top
		tess.addVertexWithUV(-s, 0.8F, -s, 0, 0); tess.addVertexWithUV(s, 0.8F, -s, 1, 0); tess.addVertexWithUV(s, 0.8F, s, 1, 1); tess.addVertexWithUV(-s, 0.8F, s, 0, 1);
		// north
		tess.addVertexWithUV(-s, 0, -s, 0, 1); tess.addVertexWithUV(s, 0, -s, 1, 1); tess.addVertexWithUV(s, 0.8F, -s, 1, 0); tess.addVertexWithUV(-s, 0.8F, -s, 0, 0);
		// south
		tess.addVertexWithUV(-s, 0, s, 0, 1); tess.addVertexWithUV(-s, 0.8F, s, 0, 0); tess.addVertexWithUV(s, 0.8F, s, 1, 0); tess.addVertexWithUV(s, 0, s, 1, 1);
		// west
		tess.addVertexWithUV(-s, 0, -s, 0, 1); tess.addVertexWithUV(-s, 0.8F, -s, 0, 0); tess.addVertexWithUV(-s, 0.8F, s, 1, 0); tess.addVertexWithUV(-s, 0, s, 1, 1);
		// east
		tess.addVertexWithUV(s, 0, -s, 0, 1); tess.addVertexWithUV(s, 0, s, 1, 1); tess.addVertexWithUV(s, 0.8F, s, 1, 0); tess.addVertexWithUV(s, 0.8F, -s, 0, 0);
		tess.draw();

		GL11.glPopMatrix();
	}

	@Override
	protected ResourceLocation getEntityTexture(Entity entity) {
		return ResourceManager.drop_pod_tex; // 占位贴图
	}

}
