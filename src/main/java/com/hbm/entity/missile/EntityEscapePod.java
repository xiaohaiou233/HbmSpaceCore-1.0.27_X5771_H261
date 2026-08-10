package com.hbm.entity.missile;

import com.hbm.config.SpaceConfig;
import com.hbm.dim.CelestialBody;
import com.hbm.dim.SolarSystem;
import com.hbm.dim.spawn.SpawnTeleporter;
import com.hbm.handler.RocketStruct;
import com.hbm.items.ItemVOTVdrive.Destination;
import com.hbm.items.ItemVOTVdrive.Target;
import com.hbm.items.ModItems;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

// ==== [modpack] 逃生舱：继承原版火箭实体，完全复用登陆舱的降落状态机/动画/骑乘/下舱。
// 只改 4 处：getTarget（Duna）、getDestination（随机落点）、attemptLaunch（无视燃料）、onImpact（setLanded + 转降落仓）
public class EntityEscapePod extends EntityRideableRocket {

	private int destX;
	private int destZ;

	public EntityEscapePod(World world) {
		super(world);
		initCapsule();
		initDest();
	}

	public EntityEscapePod(World world, float x, float y, float z) {
		super(world);
		setPosition(x, y, z);
		targetX = (int)x - 10000;
		targetZ = (int)z;
		initCapsule();
		initDest();
	}

	// 裸 APOLLO 座舱（复用着陆器座舱模型/飞行）；setSize 会置 sizeSet=true，跳过原版自动停靠检查（端口已停靠）
	private void initCapsule() {
		RocketStruct struct = new RocketStruct(new ItemStack(ModItems.rp_capsule_20));
		setRocket(struct);
		setSize(2, (float)struct.getHeight() + 1);
	}

	// 随机落点缓存（一次性，构造时定；与掉入火星同一随机方式：±maxProbeDistance, y=800）
	private void initDest() {
		destX = worldObj.rand.nextInt(SpaceConfig.maxProbeDistance * 2) - SpaceConfig.maxProbeDistance;
		destZ = worldObj.rand.nextInt(SpaceConfig.maxProbeDistance * 2) - SpaceConfig.maxProbeDistance;
	}

	@Override
	public Target getTarget() {
		// 逃生舱固定目的地：Duna 地表
		return new Target(CelestialBody.getBody("duna"), false, true);
	}

	@Override
	public Destination getDestination() {
		return new Destination(SolarSystem.Body.DUNA, destX, destZ);
	}

	@Override
	public void attemptLaunch() {
		// [modpack] 逃生舱无视燃料直接发射（一次性道具）
		// 出生维度经 getTarget 特判读成在轨 Duna → from.inOrbit=true → UNDOCKING（同站里脱离）
		Target from = CelestialBody.getTarget(worldObj, (int)posX, (int)posZ);
		Target to = getTarget();
		RocketState transitionTo = from.inOrbit ? RocketState.UNDOCKING : RocketState.LAUNCHING;
		targetX = (int)posX - 10000;
		targetZ = (int)posZ;
		setState(transitionTo);
	}

	@Override
	protected void onImpact(MovingObjectPosition mop) {
		super.onImpact(mop);
		// [modpack] 落地（裸舱 stages==0 → 安全 LANDED）：只设重生点。
		// 不在此转降落仓/销毁 —— 让玩家按 shift 正常下舱（复用原版下舱方式），下舱后由 onUpdate 转换
		if(getState() == RocketState.LANDED && !worldObj.isRemote && riddenByEntity instanceof EntityPlayer) {
			SpawnTeleporter.setLanded((EntityPlayer) riddenByEntity, (int)posX, (int)posY, (int)posZ);
		}
	}

	@Override
	public void onUpdate() {
		// [perf] 临时监测：逃生舱每 tick 逻辑耗时（含父类飞行状态机）
		long t0 = System.nanoTime();
		super.onUpdate();
		// [modpack] 玩家下舱后（LANDED 且无乘员）→ 就地转成降落仓（服务端）
		// 停靠于接口时是 AWAITING，不会触发；落地后 LANDED，shift 下舱后 riddenByEntity 为空 → 转换
		if(!worldObj.isRemote && getState() == RocketState.LANDED && riddenByEntity == null) {
			EntityLootPod pod = new EntityLootPod(worldObj, posX, posY, posZ);
			worldObj.spawnEntityInWorld(pod);
			setDead();
		}
		long t1 = System.nanoTime();
		if(t1 - t0 > 5_000_000) {
			com.hbm.main.MainRegistry.logger.info("[perf] escapePod.onUpdate took " + ((t1 - t0) / 1000000) + "ms, state=" + getState() + " pos=" + (int)posX + "," + (int)posY + "," + (int)posZ);
		}
	}

	@Override
	public boolean canBePushed() {
		// [modpack] 停靠(AWAITING)于端口时不可被玩家/实体撞离端口（原版 canBePushed=true 会被撞得水平漂移，
		// AWAITING 分支又每 tick 只清 motion 不回锚 → docked 状态与位置脱节）；飞行/落地仍可推（原版行为）
		return getState() != RocketState.AWAITING;
	}

	@Override
	public boolean isEntityInvulnerable()
	{
		return getState() == RocketState.AWAITING;
	}
}
