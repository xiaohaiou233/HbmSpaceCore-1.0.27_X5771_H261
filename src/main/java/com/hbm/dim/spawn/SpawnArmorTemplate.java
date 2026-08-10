package com.hbm.dim.spawn;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import com.hbm.config.SpaceConfig;
import com.hbm.main.MainRegistry;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

// ==== [modpack] 进服护甲模板：/hbmtest armor save 快照当前穿戴 → config/hbmcore_spawnarmor.dat
// 纯模板驱动：文件不存在/为空 → applyToPlayer 静默跳过（不给任何装备）。
// ItemStack.writeToNBT 完整保留电量、盔甲插件（含氧罐）、附魔、自定义名、耐久。
public class SpawnArmorTemplate {

	public static final int VERSION = 1;
	public static final String FILE_NAME = "hbmcore_spawnarmor.dat";

	private static File getFile() {
		return new File(MainRegistry.configDir, FILE_NAME);
	}

	// 读模板 → 有则穿上 4 槽（完整 NBT），并强制同步客户端
	// 返回 true = 已穿上；false = 未启用/无模板/损坏（静默跳过）
	public static boolean applyToPlayer(EntityPlayer player) {
		if(!SpaceConfig.giveSpawnArmor) return false;
		if(player.worldObj.isRemote) return false;

		NBTTagCompound nbt = load();
		if(nbt == null) return false;

		NBTTagList list = nbt.getTagList("armor", 10);
		ItemStack[] armor = player.inventory.armorInventory;
		for(int i = 0; i < 4 && i < list.tagCount(); i++) {
			armor[i] = ItemStack.loadItemStackFromNBT(list.getCompoundTagAt(i));
		}

		// PlayerLoggedInEvent 触发时初始背包包已下发客户端，直接改槽位不会同步 → 强制补发
		if(player instanceof EntityPlayerMP) {
			((EntityPlayerMP) player).inventoryContainer.detectAndSendChanges();
		}

		MainRegistry.logger.info("[hbmcore] SpawnArmorTemplate applied " + list.tagCount() + " pieces to " + player.getCommandSenderName());
		return true;
	}

	// 快照当前穿戴 → 写模板文件；返回写入件数（-1 = 失败）
	public static int saveFromPlayer(EntityPlayer player) {
		if(player.worldObj.isRemote) return -1;

		NBTTagList list = new NBTTagList();
		ItemStack[] armor = player.inventory.armorInventory;
		int count = 0;
		for(int i = 0; i < 4; i++) {
			NBTTagCompound slot = new NBTTagCompound();
			if(armor[i] != null) {
				armor[i].writeToNBT(slot);
				count++;
			}
			list.appendTag(slot); // 空槽存空 compound（load 时 loadItemStackFromNBT 返回 null）
		}

		NBTTagCompound root = new NBTTagCompound();
		root.setInteger("version", VERSION);
		root.setTag("armor", list);

		try {
			FileOutputStream fos = new FileOutputStream(getFile());
			CompressedStreamTools.writeCompressed(root, fos);
			fos.close();
			MainRegistry.logger.info("[hbmcore] SpawnArmorTemplate saved " + count + " pieces to " + getFile().getAbsolutePath());
			return count;
		} catch(Exception e) {
			MainRegistry.logger.warn("[hbmcore] SpawnArmorTemplate save failed", e);
			return -1;
		}
	}

	// 删除模板文件；返回是否存在
	public static boolean clear() {
		File f = getFile();
		boolean existed = f.exists() && f.isFile();
		if(existed) {
			f.delete();
			MainRegistry.logger.info("[hbmcore] SpawnArmorTemplate cleared");
		}
		return existed;
	}

	public static boolean exists() {
		File f = getFile();
		return f.exists() && f.isFile() && f.length() > 0;
	}

	// 读模板；损坏/版本不符 → WARN 日志 + 返回 null（不中断调用方）
	public static NBTTagCompound load() {
		File f = getFile();
		if(!f.exists() || !f.isFile()) return null;
		try {
			FileInputStream fis = new FileInputStream(f);
			NBTTagCompound nbt = CompressedStreamTools.readCompressed(fis);
			fis.close();
			if(nbt.getInteger("version") != VERSION) {
				MainRegistry.logger.warn("[hbmcore] SpawnArmorTemplate version mismatch: " + nbt.getInteger("version"));
				return null;
			}
			return nbt;
		} catch(Exception e) {
			MainRegistry.logger.warn("[hbmcore] SpawnArmorTemplate read failed", e);
			return null;
		}
	}

	// 打印模板状态 + 4 槽物品显示名（供 show）
	public static String describe() {
		if(!exists()) return "模板不存在（进服不会给任何装备）";
		NBTTagCompound nbt = load();
		if(nbt == null) return "模板存在但损坏/版本不符";
		NBTTagList list = nbt.getTagList("armor", 10);
		StringBuilder sb = new StringBuilder("模板存在，4 槽：");
		for(int i = 0; i < 4; i++) {
			ItemStack stack = i < list.tagCount() ? ItemStack.loadItemStackFromNBT(list.getCompoundTagAt(i)) : null;
			sb.append("\n  槽 ").append(i).append(": ").append(stack == null ? "空" : stack.getDisplayName());
		}
		return sb.toString();
	}

}
