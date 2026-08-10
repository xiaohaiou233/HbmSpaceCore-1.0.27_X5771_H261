package com.hbm.render.entity;

import org.lwjgl.opengl.GL11;

import com.hbm.main.ResourceManager;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

// ==== [modpack] 降落仓渲染：复用着陆舱（landing_capsule）模型 + 贴图（逃生舱落地后的残骸就长这样）
// 不再是占位立方体
public class RenderLootPod extends Render {

	@Override
	public void doRender(Entity entity, double x, double y, double z, float f, float interp) {
		GL11.glPushMatrix();
		GL11.glTranslated(x, y, z);
		// 直立（逃生舱本来就是垂直降落/着陆），模型底部锚定地面
		bindEntityTexture(entity);
		ResourceManager.landing_capsule.renderAll();
		GL11.glPopMatrix();
	}

	@Override
	protected ResourceLocation getEntityTexture(Entity entity) {
		return ResourceManager.landing_capsule_tex;
	}

}
