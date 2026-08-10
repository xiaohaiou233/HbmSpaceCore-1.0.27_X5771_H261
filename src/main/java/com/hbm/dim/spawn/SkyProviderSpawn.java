package com.hbm.dim.spawn;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.hbm.dim.CelestialBody;
import com.hbm.dim.SkyProviderCelestial;
import com.hbm.dim.SolarSystem;
import com.hbm.dim.SolarSystem.AstroMetric;
import com.hbm.dim.WorldProviderCelestial;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.util.Vec3;

// ==== [modpack] 出生维度天空：空间站视角（仿 SkyProviderOrbit）
// 出生维度是"空间站看所绕天体"的视角：深空 + 星空 + 太阳 + Duna 巨大星球
// 复用 SkyProviderCelestial 的渲染原语（renderStars/renderSun/renderCelestials）
// 不使用 OrbitalStation（避免全局 clientStation 干扰），直接用出生维度 metrics + Duna
public class SkyProviderSpawn extends SkyProviderCelestial {

	@Override
	public void render(float partialTicks, WorldClient world, Minecraft mc) {
		// [modpack] 先确保 metrics 已计算（getSkyColor 触发 updateSky 可能在 render 之后才调用，导致火星不渲染）
		WorldProviderSpawn spawnProvider = (WorldProviderSpawn) world.provider;
		spawnProvider.ensureSkyMetrics(partialTicks);
		WorldProviderCelestial provider = spawnProvider;
		CelestialBody duna = CelestialBody.getBody("duna");
		float orbitalTilt = 80;

		GL11.glDepthMask(false);
		GL11.glDisable(GL11.GL_FOG);
		GL11.glDisable(GL11.GL_ALPHA_TEST);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glEnable(GL11.GL_BLEND);
		RenderHelper.disableStandardItemLighting();
		OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);

		float solarAngle = world.getCelestialAngle(partialTicks);
		float siderealAngle = (float)SolarSystem.calculateSiderealAngle(world, partialTicks, duna);
		float starBrightness = world.getStarBrightness(partialTicks);

		renderStars(partialTicks, world, mc, starBrightness, solarAngle + siderealAngle, orbitalTilt);

		GL11.glPushMatrix();
		{
			GL11.glRotatef(orbitalTilt, 1.0F, 0.0F, 0.0F);
			GL11.glRotatef(-90.0F, 0.0F, 1.0F, 0.0F);
			GL11.glRotatef(solarAngle * 360.0F, 1.0F, 0.0F, 0.0F);
			OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);

			double sunSize = SolarSystem.calculateSunSize(duna) * SolarSystem.SUN_RENDER_SCALE;
			double coronaSize = sunSize * 3;
			renderSun(partialTicks, world, mc, duna.getStar(), sunSize, coronaSize, 1, 0);

			// 过滤掉站本身（spawnBody，纹理是火星色），否则会被当成一个"小 Duna"画进天空跟着太阳转
			List<AstroMetric> skyMetrics = new ArrayList<>();
			CelestialBody spawnBody = CelestialBody.getBody("spawn");
			for(AstroMetric m : provider.metrics) {
				if(m.body != spawnBody) skyMetrics.add(m);
			}
			renderCelestials(partialTicks, world, mc, skyMetrics, solarAngle, null, Vec3.createVectorHelper(0, 0, 0), 1, 1, duna, SolarSystem.MAX_APPARENT_SIZE_ORBIT);
		}
		GL11.glPopMatrix();

		GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glEnable(GL11.GL_ALPHA_TEST);
		GL11.glEnable(GL11.GL_FOG);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glDepthMask(true);
	}

}
