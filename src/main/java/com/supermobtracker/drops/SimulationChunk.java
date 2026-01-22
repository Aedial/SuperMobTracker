package com.supermobtracker.drops;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;


/**
 * A minimal empty chunk for simulation worlds.
 * Works on both client and server (unlike EmptyChunk which is client-only).
 * Returns air for all blocks and prevents any modifications.
 */
public class SimulationChunk extends Chunk {

    public SimulationChunk(World worldIn, int x, int z) {
        super(worldIn, x, z);
    }

    @Override
    public boolean isAtLocation(int x, int z) {
        return true; // Accept any chunk coords - we're a universal empty chunk
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return Blocks.AIR.getDefaultState();
    }

    @Override
    public IBlockState getBlockState(int x, int y, int z) {
        return Blocks.AIR.getDefaultState();
    }

    @Override
    public IBlockState setBlockState(BlockPos pos, IBlockState state) {
        return null; // Don't allow block changes
    }

    @Override
    public int getLightFor(EnumSkyBlock type, BlockPos pos) {
        return type == EnumSkyBlock.SKY ? 15 : 0;
    }

    @Override
    public void setLightFor(EnumSkyBlock type, BlockPos pos, int value) {
        // Ignore light changes
    }

    @Override
    public int getLightSubtracted(BlockPos pos, int amount) {
        return 15 - amount; // Full light minus subtraction
    }

    @Override
    public TileEntity getTileEntity(BlockPos pos, Chunk.EnumCreateEntityType type) {
        return null; // No tile entities
    }

    @Override
    public void addTileEntity(TileEntity tileEntityIn) {
        // Don't add tile entities
    }

    @Override
    public void addTileEntity(BlockPos pos, TileEntity tileEntityIn) {
        // Don't add tile entities
    }

    @Override
    public void removeTileEntity(BlockPos pos) {
        // Nothing to remove
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public boolean isEmptyBetween(int startY, int endY) {
        return true;
    }
}
