package com.hbm.creativetabs;

import com.hbm.items.ModItems;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Items;
import net.minecraft.item.Item;

// ==== [modpack] 创造页 HBMCore：图标用着陆舱（rp_c_20）的物品模型，逃生舱放这里
public class HbmCoreTab extends CreativeTabs {

	public HbmCoreTab(int p_i1853_1_, String p_i1853_2_) {
		super(p_i1853_1_, p_i1853_2_);
	}

	@Override
	public Item getTabIconItem() {
		if(ModItems.rp_capsule_20 != null) return ModItems.rp_capsule_20;
		return Items.iron_pickaxe;
	}

}
