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
		setSize(0.9F, 0.9F);
	}

	public EntityLootPod(World world, double x, double y, double z) {
		this(world);
		setPosition(x, y + 0.5D, z);
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
	protected void readEntityFromNBT(NBTTagCompound nbt) {}

	@Override
	protected void writeEntityToNBT(NBTTagCompound nbt) {}

}
