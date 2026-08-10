package com.hbm.world;

import java.util.ArrayList;

import com.hbm.config.SpaceConfig;
import com.hbm.dim.CelestialBody;
import com.hbm.dim.SolarSystem;
import com.hbm.lib.RefStrings;
import com.hbm.dim.dres.WorldGeneratorDres;
import com.hbm.dim.WorldGeneratorCelestial;
import com.hbm.dim.WorldProviderEarth;
import com.hbm.dim.Ike.WorldGeneratorIke;
import com.hbm.dim.Ike.WorldProviderIke;
import com.hbm.dim.dres.WorldProviderDres;
import com.hbm.dim.duna.WorldGeneratorDuna;
import com.hbm.dim.duna.WorldProviderDuna;
import com.hbm.dim.eve.WorldGeneratorEve;
import com.hbm.dim.eve.WorldProviderEve;
import com.hbm.dim.laythe.WorldGeneratorLaythe;
import com.hbm.dim.laythe.WorldProviderLaythe;
import com.hbm.dim.minmus.WorldGeneratorMinmus;
import com.hbm.dim.minmus.WorldProviderMinmus;
import com.hbm.dim.moho.WorldGeneratorMoho;
import com.hbm.dim.moho.WorldProviderMoho;
import com.hbm.dim.moon.WorldGeneratorMoon;
import com.hbm.dim.moon.WorldProviderMoon;
import com.hbm.dim.orbit.WorldProviderOrbit;
import com.hbm.dim.spawn.WorldProviderSpawn;
import com.hbm.dim.tekto.WorldGeneratorTekto;
import com.hbm.dim.tekto.WorldProviderTekto;
import com.hbm.dim.thatmo.WorldProviderThatmo;
import com.hbm.util.BobMathUtil;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldProvider;
import net.minecraftforge.common.DimensionManager;

public class PlanetGen {

	public static void init() {
		registerDimension(SpaceConfig.moonDimension, WorldProviderMoon.class);
		registerDimension(SpaceConfig.dunaDimension, WorldProviderDuna.class);
		registerDimension(SpaceConfig.ikeDimension, WorldProviderIke.class);
		registerDimension(SpaceConfig.eveDimension, WorldProviderEve.class);
		registerDimension(SpaceConfig.dresDimension, WorldProviderDres.class);
		registerDimension(SpaceConfig.mohoDimension, WorldProviderMoho.class);
		registerDimension(SpaceConfig.minmusDimension, WorldProviderMinmus.class);
		registerDimension(SpaceConfig.laytheDimension, WorldProviderLaythe.class);
		registerDimension(SpaceConfig.orbitDimension, WorldProviderOrbit.class);
		registerDimension(SpaceConfig.tektoDimension, WorldProviderTekto.class);
		registerDimension(SpaceConfig.thatmoDimension, WorldProviderThatmo.class);

		// ==== [modpack] 出生维度：不加入 spaceDimensions（不出现在星图/旅行目标）
		DimensionManager.registerProviderType(SpaceConfig.spawnDimension, WorldProviderSpawn.class, false);
		DimensionManager.registerDimension(SpaceConfig.spawnDimension, SpaceConfig.spawnDimension);

		// ==== [modpack] 出生维度虚拟天体：无大气（真空），作为 Duna 卫星（天空显示深空 + 火星）
		// 轨道高度 12000km（Duna 半径 320km）→ 火星清晰可见但不占满天空，背景深空 + 星空
		CelestialBody duna = CelestialBody.getBody("duna");
		CelestialBody spawnBody = new CelestialBody("spawn", SpaceConfig.spawnDimension, SolarSystem.Body.SPAWN);
		// 纹理必须非 null：SkyProviderCelestial 渲染会 bind body.texture / surfaceTexture（真空尘埃粒子），复用火星纹理
		spawnBody.withTexture(new ResourceLocation(RefStrings.MODID, "textures/misc/space/duna.png"));
		spawnBody.withBlockTextures(RefStrings.MODID + ":textures/blocks/duna_rock.png", RefStrings.MODID + ":textures/blocks/duna_sands.png");
		// [modpack] 轨道距离：先用第一版数据（12000km）复现 Duna 显示
		// 734km（空间站巨大视角，apparentSize≈148）时 Duna 没显示，待确认可见后再调回空间站视角合适大小
		// massKg=0 → getSurfaceGravity=0 → 出生维度太空 0 重力（漂浮）
		spawnBody.withMassRadius(0, 150).withOrbitalParameters(12_000, 0, 0, 0, 0);
		duna.satellites.add(spawnBody);
		spawnBody.parent = duna;

		// Register our ore providers
		GameRegistry.registerWorldGenerator(new WorldGeneratorCelestial(), 2);

		GameRegistry.registerWorldGenerator(new WorldGeneratorMoon(), 1);
		GameRegistry.registerWorldGenerator(new WorldGeneratorDuna(), 1);
		GameRegistry.registerWorldGenerator(new WorldGeneratorIke(), 1);
		GameRegistry.registerWorldGenerator(new WorldGeneratorEve(), 1);
		GameRegistry.registerWorldGenerator(new WorldGeneratorDres(), 1);
		GameRegistry.registerWorldGenerator(new WorldGeneratorMoho(), 1);
		GameRegistry.registerWorldGenerator(new WorldGeneratorMinmus(), 1);
		GameRegistry.registerWorldGenerator(new WorldGeneratorLaythe(), 1);
		GameRegistry.registerWorldGenerator(new WorldGeneratorTekto(), 1);
	}

	private static ArrayList<Integer> spaceDimensions = new ArrayList<>();

	public static int[] getSpaceDimensions() {
		return BobMathUtil.intCollectionToArray(spaceDimensions);
	}

	private static void registerDimension(int dimensionId, Class<? extends WorldProvider> clazz) {
		DimensionManager.registerProviderType(dimensionId, clazz, false);
		DimensionManager.registerDimension(dimensionId, dimensionId);

		if(dimensionId != SpaceConfig.orbitDimension) spaceDimensions.add(dimensionId);
	}

	public static void overrideOverworldProvider() {
		DimensionManager.unregisterProviderType(0);
		DimensionManager.registerProviderType(0, WorldProviderEarth.class, true);
	}

}

