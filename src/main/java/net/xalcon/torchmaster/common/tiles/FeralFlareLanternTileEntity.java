package net.xalcon.torchmaster.common.tiles;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.xalcon.torchmaster.TorchmasterConfig;
import net.xalcon.torchmaster.common.ModBlocks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FeralFlareLanternTileEntity extends BlockEntity implements ITickableTileEntity
{
    private FakePlayer fakePlayer;
    private int ticks;
    private boolean useLineOfSight;
    private List<BlockPos> childLights = new ArrayList<>();

    public FeralFlareLanternTileEntity()
    {
        super(ModBlocks.tileFeralFlareLantern);
    }

    @Override
    public void tick()
    {
        if(this.level.isClientSide || ++this.ticks % TorchmasterConfig.GENERAL.feralFlareTickRate.get() != 0) return;
        if(this.childLights.size() > TorchmasterConfig.GENERAL.feralFlareLanternLightCountHardcap.get()) return;
        ticks = 0;

        if(fakePlayer == null)
        {
            fakePlayer = FakePlayerFactory.get((ServerLevel) level, new GameProfile(UUID.fromString("2282ab80-e482-11e9-81b4-2a2ae2dbcce4"), "TorchMasterFeralFlareLantern"));
        }

        int radius = TorchmasterConfig.GENERAL.feralFlareRadius.get();
        int diameter = radius * 2;

        int x = (radius - this.level.random.nextInt(diameter)) + this.pos.getX();
        int y = (radius - this.level.random.nextInt(diameter)) + this.pos.getY();
        int z = (radius - this.level.random.nextInt(diameter)) + this.pos.getZ();

        // limit height - lower bounds
        if (y < 3) y = 3;

        // limit height - upper bounds
        BlockPos targetPos = new BlockPos(x, y, z);
        BlockPos surfaceHeight = this.level.getHeight(Heightmap.Types.WORLD_SURFACE, targetPos);
        if (targetPos.getY() > surfaceHeight.getY() + 4)
            targetPos = surfaceHeight.up(4);

        // dont try to place blocks outside of the world height
        int worldHeightCap = level.getHeight();
        if(targetPos.getY() > worldHeightCap)
            targetPos = new BlockPos(targetPos.getX(), worldHeightCap - 1, targetPos.getZ());

        if(!this.level.isBlockLoaded(targetPos)) return;

        if (this.level.isAirBlock(targetPos) && this.level.getLightFor(LightType.BLOCK, targetPos) < TorchmasterConfig.GENERAL.feralFlareMinLightLevel.get())
        {
            if(this.useLineOfSight)
            {
                Vector3d start = new Vector3d(targetPos.getX(), targetPos.getY(), targetPos.getZ()).add(0.5, 0.5, 0.5);
                Vector3d end = new Vector3d(this.pos.getX(), this.pos.getY(), this.pos.getZ()).add(0.5, 0.5, 0.5);
                RayTraceContext rtxCtx = new RayTraceContext(start, end, RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.ANY, fakePlayer);
                BlockRayTraceResult rtResult = level.rayTraceBlocks(rtxCtx);

                if(rtResult.getType() == RayTraceResult.Type.BLOCK)
                {
                    BlockPos hitPos = rtResult.getPos();
                    if(!(hitPos.getX() == this.pos.getX() && hitPos.getY() == this.pos.getY() && hitPos.getZ() == this.pos.getZ()))
                        return;
                }
            }

            if(this.level.setBlockState(targetPos, ModBlocks.blockInvisibleLight.getDefaultState(), 3))
            {
                this.childLights.add(targetPos);
                this.markDirty();
            }
        }
    }

    @Override
    public void read(BlockState blockState, CompoundNBT nbt)
    {
        this.childLights.clear();
        if(nbt.getTagId("lights") == Constants.NBT.TAG_INT_ARRAY)
        {
            BlockPos origin = new BlockPos(nbt.getInt("x"), nbt.getInt("y"),nbt.getInt("z"));
            int[] lightsEncoded = ((IntArrayNBT) nbt.get("lights")).getIntArray();
            for(int encodedLight : lightsEncoded)
                this.childLights.add(decodePosition(origin, encodedLight));
        }
        this.ticks = nbt.getInt("ticks");
        this.useLineOfSight = nbt.getBoolean("useLoS");
        super.read(blockState, nbt);
    }

    @Override
    public CompoundNBT write(CompoundNBT nbt)
    {
        List<Integer> childLightsEncoded = new ArrayList<>(this.childLights.size());
        for(BlockPos child : this.childLights)
            childLightsEncoded.add(encodePosition(this.pos, child));

        nbt.put("lights", new IntArrayNBT(childLightsEncoded));
        nbt.putInt("ticks", this.ticks);
        nbt.putBoolean("useLoS", this.useLineOfSight);
        return super.write(nbt);
    }

    public void setUseLineOfSight(boolean state)
    {
        this.useLineOfSight = state;
        this.markDirty();
        BlockState blockState = this.world.getBlockState(this.pos);
        this.world.notifyBlockUpdate(this.pos, blockState, blockState, 0);
    }

    public boolean shouldUseLineOfSight()
    {
        return this.useLineOfSight;
    }

    public void removeChildLights()
    {
        if(this.world.isRemote) return;
        for(var pos : this.childLights)
        {
            if (this.world.getBlockState(pos).getBlock() == ModBlocks.blockInvisibleLight)
            {
                this.world.removeBlock(pos, false);
            }
        }
        this.childLights.clear();
    }

    private static int encodePosition(BlockPos origin, BlockPos target)
    {
        int x = target.getX() - origin.getX();
        int y = target.getY() - origin.getY();
        int z = target.getZ() - origin.getZ();
        return ((x & 0xFF) << 16) + ((y & 0xFF) << 8) + (z & 0xFF);
    }

    private static BlockPos decodePosition(BlockPos origin, int pos)
    {
        int x = (byte)((pos >> 16) & 0xFF);
        int y = (byte)((pos >> 8) & 0xFF);
        int z = (byte)(pos & 0xFF);
        return origin.offset(x, y, z);
    }
}
