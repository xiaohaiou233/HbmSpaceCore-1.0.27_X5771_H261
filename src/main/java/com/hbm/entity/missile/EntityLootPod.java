package com.hbm.entity.missile;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

// ==== [modpack] 降落仓残骸：逃生舱落地后就地转成它。右键拾取掉铁锭（功能测试，后续换生存资源）
public class EntityLootPod extends Entity {

	public EntityLootPod(World world) {
		super(world);
		// [modpack] hitbox 对齐 landing_capsule 模型（3.75 高，宽约 2.1-2.85）：原 0.9×0.9 只有模型底部可右键/碰撞，
		// 模型上部 2.85 格完全不可交互。宽用 2.0 与逃生舱一致（略窄于模型裙摆，可接受）。
		setSize(2.0F, 3.75F);
	}

	public EntityLootPod(World world, double x, double y, double z) {
		this(world);
		// [modpack] y 直接作为底部（RenderLootPod 的立方体底部锚定 local y=0），贴地
		setPosition(x, y, z);
		noClip = true;
	}

	@Override
	protected void entityInit() {}

	@Override
	public boolean interactFirst(EntityPlayer player) {
		if(!worldObj.isRemote) {
			// [modpack] 掉落表：铁锭 ×1（功能测试）
			worldObj.spawnEntityInWorld(new EntityItem(worldObj, posX, posY + 0.5D, posZ, new ItemStack(Items.iron_ingot)));
			setDead();
		}
		return true;
	}

	@Override
	public boolean canBeCollidedWith() {
		return true;
	}

	@Override
	protected void readEntityFromNBT(NBTTagCompound nbt) {
		// ==== [modpack] persist noClip and size across world reload
		this.noClip = nbt.getBoolean("noClip");
		setSize(nbt.hasKey("width") ? nbt.getFloat("width") : 0.9F,
				nbt.hasKey("height") ? nbt.getFloat("height") : 0.9F);
	}

	@Override
	protected void writeEntityToNBT(NBTTagCompound nbt) {
		// ==== [modpack] persist noClip and size across world reload
		nbt.setBoolean("noClip", noClip);
		nbt.setFloat("width", width);
		nbt.setFloat("height", height);
	}

}
