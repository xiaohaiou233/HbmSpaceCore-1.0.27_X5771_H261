package com.hbm.dim.spawn;

import com.hbm.dim.CelestialBody;
import com.hbm.dim.SolarSystem;
import com.hbm.dim.WorldProviderCelestial;
import com.hbm.dim.orbit.BiomeGenOrbit;
import com.hbm.dim.orbit.ChunkProviderOrbit;
import com.hbm.dim.orbit.WorldProviderOrbit;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.world.biome.WorldChunkManagerHell;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.client.IRenderHandler;

// ==== [modpack] 出生维度：仿近地轨道（虚空），玩家在此出生，后续放置"破烂空间站"
// 继承 WorldProviderCelestial：天空用 SkyProviderCelestial（深空黑天空 + 星空 + 天体）
// 虚拟天体 spawnBody（Duna 卫星、无大气）→ 深空 + 火星在天空可见，且真空无氧
public class WorldProviderSpawn extends WorldProviderCelestial {

	@Override
	public void registerWorldChunkManager() {
		this.worldChunkMgr = new WorldChunkManagerHell(BiomeGenOrbit.biome, 0.0F);
	}

	@Override
	public String getDimensionName() {
		return "Spawn";
	}

	@Override
	public IChunkProvider createChunkGenerator() {
		// 复用近地轨道的虚空区块生成
		return new ChunkProviderOrbit(this.worldObj);
	}

	@Override
	public boolean canDoLightning(Chunk chunk) {
		return false;
	}

	@Override
	public boolean canDoRainSnowIce(Chunk chunk) {
		return false;
	}

	@Override
	public int getRespawnDimension(EntityPlayerMP player) {
		// ==== [modpack] 重生维度：床记录 → 床所在维度；否则 hasLanded ? Duna : SPAWN
		// （详见 SpawnTeleporter.resolveRespawnDimension）
		return com.hbm.dim.spawn.SpawnTeleporter.resolveRespawnDimension(player);
	}

	@Override
	public boolean canRespawnHere() {
		return true;
	}

	private IRenderHandler skyProvider;

	// [modpack] 空间站视角：用 calculateMetricsFromSatellite（原版空间站方式）而非地表视角的 calculateMetricsFromBody
	// 原 WorldProviderCelestial.updateSky（FromBody）会让火星随外层太阳旋转而转；Satellite 方式 angle 补偿使火星固定
	private float celestialAngle;
	// ==== [modpack] 本地存一份日食因子：父类 WorldProviderCelestial 的 eclipseAmount 是 private 子类不可写，
	// 而下方 getStarBrightness 复用 ORBIT 公式需要它 → 在 updateSky 里用同一静态 getEclipseFactor 算一份
	// 类型用 double（getEclipseFactor 返回 double，与 WorldProviderOrbit 的字段一致），用处在 getStarBrightness 里 (float) 强转
	private double eclipseAmount;

	@Override
	@SideOnly(Side.CLIENT)
	protected void updateSky(float partialTicks) {
		CelestialBody duna = CelestialBody.getBody("duna");
		// 原 updateSky 还计算 eclipseAmount（日食因子），但那是 WorldProviderCelestial 的 private 字段，子类无法写；
		// 出生维度无大气（黑天空），日食变暗影响很小，省略（但下方 getStarBrightness 复用 ORBIT 公式需要 → 本地算一份）
		metrics = SolarSystem.calculateMetricsFromSatellite(worldObj, partialTicks, duna, WorldProviderOrbit.getOrbitalAltitude(duna));
		// 站视角太阳角度（匹配原版 Orbit）：外层旋转用它，与火星 angle 补偿 → 火星固定不随太阳转
		float angle = (float)SolarSystem.calculateSingleAngle(worldObj, partialTicks, metrics, duna, WorldProviderOrbit.getOrbitalAltitude(duna));
		celestialAngle = 0.5F - (angle / 360.0F);

		// ==== [modpack] 补算 eclipseAmount（仿 WorldProviderOrbit.updateSky），供下方 getStarBrightness 复用
		double sunSize = SolarSystem.calculateSunSize(duna);
		eclipseAmount = WorldProviderCelestial.getEclipseFactor(metrics, sunSize, SolarSystem.MAX_APPARENT_SIZE_ORBIT);
	}

	// 站视角太阳角度（像原版 WorldProviderOrbit），而非 Celestial 的 spawnBody 自转日长——否则外层旋转速率与火星 angle 不匹配导致火星转
	@Override
	public float calculateCelestialAngle(long worldTime, float partialTicks) {
		return celestialAngle;
	}

	// ==== [modpack] 复用 ORBIT 的星星亮度公式（WorldProviderOrbit.getStarBrightness），脱离昼夜振荡：
	// 原继承 WorldProviderCelestial.getStarBrightness 走原版昼夜公式（World.getStarBrightnessBody），
	// 出生维度的 calculateCelestialAngle 是旋转的 → 星星随轨道角度 0↔0.5 闪烁，且 Duna 距离因子≈0.012 兜不住
	// 现在星星亮度只取决于"离太阳距离因子"和日食度 → 太空里始终亮（Duna factor≈0.558，恒不灭）
	@Override
	@SideOnly(Side.CLIENT)
	public float getStarBrightness(float par1) {
		float distanceStart = 9_000_000;
		float distanceEnd = 30_000_000;

		// 出生维度固定绕 Duna（站），无转移进度插值
		float semiMajorAxisKm = CelestialBody.getBody("duna").getPlanet().semiMajorAxisKm;
		float distanceFactor = MathHelper.clamp_float((semiMajorAxisKm - distanceStart) / (distanceEnd - distanceStart), 0F, 1F);

		return MathHelper.clamp_float((float)eclipseAmount, distanceFactor, 1F);
	}

	// [modpack] 供 SkyProviderSpawn 确保天空 metrics 已计算：
	// getSkyColor（触发 updateSky）可能在 SkyProvider.render 之后才被调用，导致 provider.metrics 为 null，火星等天体不渲染（只剩太阳）
	public void ensureSkyMetrics(float partialTicks) {
		updateSky(partialTicks);
	}

	// ==== [modpack] 空间站视角天空：深空 + Duna 巨大（仿 SkyProviderOrbit），而非地表视角
	@Override
	@SideOnly(Side.CLIENT)
	public IRenderHandler getSkyRenderer() {
		if(skyProvider == null) skyProvider = new SkyProviderSpawn();
		return skyProvider;
	}

}
