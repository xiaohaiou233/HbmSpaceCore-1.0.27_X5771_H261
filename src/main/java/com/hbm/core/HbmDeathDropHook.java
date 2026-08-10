package com.hbm.core;

import com.hbm.config.SpaceConfig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

// ==== [modpack] 盔甲槽死亡保留回调（被 HbmDeathDropTransformer 注入到 vanilla EntityPlayer.onDeath）
// 注入位置：ForgeHooks.onLivingDeath 取消检查之后、super.onDeath 之前 —— 死亡已确认，天然免疫复活甲取消时序
// 时机：vanilla dropAllItems 之前 → 置空盔甲槽后，主背包照常掉落、盔甲不掉
// 恢复：PlayerEvent.Clone(wasDeath) 灌回新玩家；PlayerLoggedInEvent 兜底"死了没重生就退出"
public class HbmDeathDropHook {

	public static final String KEEP_ARMOR_TAG = "hbmcore_keepArmor";

	// 被注入字节码调用：ALOAD 0 → this(EntityPlayer)
	// 双端都执行置空：服务端保住盔甲；客户端 vanilla dropAllItems 同样会跑（无 isRemote 守卫），
	// 若不置空就会在死亡瞬间吐幻影盔甲掉落物（纯客户端，但看起来像盔甲掉了）
	public static void saveArmorBeforeDrop(EntityPlayer player) {
		try {
			if(!SpaceConfig.armorKeepOnDeath) return;

			// 快照 4 格盔甲 → ForgeData，再置空
			ItemStack[] armor = player.inventory.armorInventory;
			NBTTagList list = new NBTTagList();
			for(int i = 0; i < 4; i++) {
				NBTTagCompound slot = new NBTTagCompound();
				if(armor[i] != null) {
					armor[i].writeToNBT(slot); // 空槽留空 compound，读回 loadItemStackFromNBT 返回 null
				}
				list.appendTag(slot);
			}
			player.getEntityData().setTag(KEEP_ARMOR_TAG, list);

			// 置空盔甲槽 → vanilla inventory.dropAllItems() 无事可掉
			for(int i = 0; i < 4; i++) {
				armor[i] = null;
			}
		} catch(Throwable t) {
			System.err.println("[hbmcore] saveArmorBeforeDrop failed: " + t);
			t.printStackTrace();
		}
	}
}
