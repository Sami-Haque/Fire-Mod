package net.minecraft.world.level.block;

import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class FireBlock extends BaseFireBlock {

   public static final int MAX_AGE = 45;
   public static final int ORIGINAL_MAX_AGE = 15;
   public static final int SCALE_AGE = MAX_AGE / ORIGINAL_MAX_AGE; // = 3

   public static final IntegerProperty AGE = IntegerProperty.create("age", 0, MAX_AGE);
   public static final BooleanProperty NORTH = PipeBlock.NORTH;
   public static final BooleanProperty EAST = PipeBlock.EAST;
   public static final BooleanProperty SOUTH = PipeBlock.SOUTH;
   public static final BooleanProperty WEST = PipeBlock.WEST;
   public static final BooleanProperty UP = PipeBlock.UP;

   private static final Map<Direction, BooleanProperty> PROPERTY_BY_DIRECTION = PipeBlock.PROPERTY_BY_DIRECTION.entrySet().stream()
           .filter(entry -> entry.getKey() != Direction.DOWN)
           .collect(Util.toMap());

   // Set to false for purely 2D flat plane spread as in the reference image
   private static final boolean ENABLE_VERTICAL_FIRE_SPREAD_IN_3D_MECHANISM = false;
   // This controls if trySpreadToAdjacent checks up/down. For flat plane fuel, up might still be relevant.
   private static final boolean ENABLE_VERTICAL_ADJACENT_SPREAD = true;


   private static final VoxelShape UP_AABB = Block.box(0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 16.0D);
   private static final VoxelShape WEST_AABB = Block.box(0.0D, 0.0D, 0.0D, 1.0D, 16.0D, 16.0D);
   private static final VoxelShape EAST_AABB = Block.box(15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
   private static final VoxelShape NORTH_AABB = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 1.0D);
   private static final VoxelShape SOUTH_AABB = Block.box(0.0D, 0.0D, 15.0D, 16.0D, 16.0D, 16.0D);
   private final Map<BlockState, VoxelShape> shapesCache;

   private final Object2IntMap<Block> igniteOdds = new Object2IntOpenHashMap<>(); // Encouragement value for fire to spread to air *from* this block as a neighbor
   private final Object2IntMap<Block> burnOdds = new Object2IntOpenHashMap<>();   // Flammability value of this block itself; higher means it's more likely to be affected by adjacent fire and contribute to ROS

   public FireBlock(BlockBehaviour.Properties properties) {
      super(properties, 1.0F);
      this.registerDefaultState(this.stateDefinition.any()
              .setValue(AGE, 0)
              .setValue(NORTH, false).setValue(EAST, false)
              .setValue(SOUTH, false).setValue(WEST, false)
              .setValue(UP, false));
      this.shapesCache = ImmutableMap.copyOf(this.stateDefinition.getPossibleStates().stream()
              .filter(state -> state.getValue(AGE) == 0)
              .collect(Collectors.toMap(Function.identity(), FireBlock::calculateShape)));
   }

   private static VoxelShape calculateShape(BlockState blockState) {
      VoxelShape shape = Shapes.empty();
      if (blockState.getValue(UP)) shape = UP_AABB;
      if (blockState.getValue(NORTH)) shape = Shapes.or(shape, NORTH_AABB);
      if (blockState.getValue(SOUTH)) shape = Shapes.or(shape, SOUTH_AABB);
      if (blockState.getValue(EAST)) shape = Shapes.or(shape, EAST_AABB);
      if (blockState.getValue(WEST)) shape = Shapes.or(shape, WEST_AABB);
      return shape.isEmpty() ? DOWN_AABB : shape; // DOWN_AABB from BaseFireBlock
   }

   @Override
   public BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor level, BlockPos currentPos, BlockPos facingPos) {
      return this.canSurvive(state, level, currentPos) ? this.getStateWithAge(level, currentPos, state.getValue(AGE)) : Blocks.AIR.defaultBlockState();
   }

   @Override
   public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      return this.shapesCache.get(state.setValue(AGE, 0));
   }

   @Override
   public BlockState getStateForPlacement(BlockPlaceContext context) {
      return this.getStateForPlacement(context.getLevel(), context.getClickedPos());
   }

   protected BlockState getStateForPlacement(BlockGetter level, BlockPos pos) {
      BlockPos belowPos = pos.below();
      BlockState belowState = level.getBlockState(belowPos);
      if (this.canBurn(belowState) || belowState.isFaceSturdy(level, belowPos, Direction.UP)) {
         return this.defaultBlockState();
      }
      BlockState placementState = this.defaultBlockState();
      for (Direction direction : Direction.values()) {
         BooleanProperty property = PROPERTY_BY_DIRECTION.get(direction);
         if (property != null) {
            placementState = placementState.setValue(property, this.canBurn(level.getBlockState(pos.relative(direction))));
         }
      }
      return placementState;
   }

   @Override
   public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
      BlockPos belowPos = pos.below();
      return level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP) || this.isValidFireLocation(level, pos);
   }

   @Override
   public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
      super.onPlace(state, level, pos, oldState, isMoving);
      level.scheduleTick(pos, this, getFireTickDelay(level.random));
   }

   @Override
   public void tick(BlockState blockState, ServerLevel serverLevel, BlockPos blockPos, RandomSource randomSource) {
      serverLevel.scheduleTick(blockPos, this, getFireTickDelay(serverLevel.random));

      if (!serverLevel.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) return;
      if (!blockState.canSurvive(serverLevel, blockPos)) {
         serverLevel.removeBlock(blockPos, false);
         return;
      }

      boolean isInfiniteBurnArea = serverLevel.getBlockState(blockPos.below()).is(serverLevel.dimensionType().infiniburn());
      int currentFireAge = blockState.getValue(AGE);

      float normalizedAgeForRain = (float) currentFireAge / SCALE_AGE;
      if (!isInfiniteBurnArea && serverLevel.isRaining() && this.isNearRain(serverLevel, blockPos) &&
              randomSource.nextFloat() < (0.2F + normalizedAgeForRain * 0.03F)) {
         serverLevel.removeBlock(blockPos, false);
         return;
      }

      int updatedFireAge = Math.min(MAX_AGE, currentFireAge + 1); // Deterministic aging
      if (currentFireAge != updatedFireAge) {
         blockState = blockState.setValue(AGE, updatedFireAge);
         serverLevel.setBlock(blockPos, blockState, Block.UPDATE_INVISIBLE);
         currentFireAge = updatedFireAge;
      }

      if (!isInfiniteBurnArea) {
         if (!this.isValidFireLocation(serverLevel, blockPos)) {
            BlockPos belowPos = blockPos.below();
            if (!serverLevel.getBlockState(belowPos).isFaceSturdy(serverLevel, belowPos, Direction.UP) || currentFireAge > (3 * SCALE_AGE)) {
               serverLevel.removeBlock(blockPos, false);
               return;
            }
         }
         if (currentFireAge == MAX_AGE && !this.canBurn(serverLevel.getBlockState(blockPos.below()))) {
            serverLevel.removeBlock(blockPos, false); // Fire burns out on non-flammable base
            return;
         }
      }

      // --- Fire Spread Mechanics ---
      boolean isIncreasedBurnoutBiome = serverLevel.getBiome(blockPos).is(BiomeTags.INCREASED_FIRE_BURNOUT);
      int biomeBurnoutEffect = isIncreasedBurnoutBiome ? -50 : 0;

      // Mechanism 1: Adjacent Block Spread (Main driver for 2D surface spread)
      // SpreadChanceParam: Lower means easier spread. Base is 300 for horizontal, 250 for vertical.
      // Biome effect makes it easier to spread in wet biomes by reducing this param.
      this.trySpreadToAdjacentFuel(serverLevel, blockPos.east(), 300 + biomeBurnoutEffect, randomSource, currentFireAge);
      this.trySpreadToAdjacentFuel(serverLevel, blockPos.west(), 300 + biomeBurnoutEffect, randomSource, currentFireAge);
      this.trySpreadToAdjacentFuel(serverLevel, blockPos.north(), 300 + biomeBurnoutEffect, randomSource, currentFireAge);
      this.trySpreadToAdjacentFuel(serverLevel, blockPos.south(), 300 + biomeBurnoutEffect, randomSource, currentFireAge);

      if (ENABLE_VERTICAL_ADJACENT_SPREAD) { // For spread to fuel blocks directly above/below
         this.trySpreadToAdjacentFuel(serverLevel, blockPos.above(), 250 + biomeBurnoutEffect, randomSource, currentFireAge);
         // Spreading down to fuel is less common in real surface fires unless embers drop, but kept for consistency.
         this.trySpreadToAdjacentFuel(serverLevel, blockPos.below(), 250 + biomeBurnoutEffect, randomSource, currentFireAge);
      }


      // Mechanism 2: 3D Air Block Ignition (Spread to nearby air blocks if they are adjacent to flammable material)
      BlockPos.MutableBlockPos mutableTargetPos = new BlockPos.MutableBlockPos();
      int startY_3D = ENABLE_VERTICAL_FIRE_SPREAD_IN_3D_MECHANISM ? -1 : 0;
      int endY_3D = ENABLE_VERTICAL_FIRE_SPREAD_IN_3D_MECHANISM ? (MAX_AGE / 10) : 0; // Limits vertical seeking if enabled

      for (int offsetX = -1; offsetX <= 1; ++offsetX) {
         for (int offsetZ = -1; offsetZ <= 1; ++offsetZ) {
            for (int offsetY = startY_3D; offsetY <= endY_3D; ++offsetY) {
               if (offsetX == 0 && offsetY == 0 && offsetZ == 0) continue;

               mutableTargetPos.setWithOffset(blockPos, offsetX, offsetY, offsetZ);

               if (serverLevel.isEmptyBlock(mutableTargetPos)) {
                  int encouragementFromNeighbors = this.getEncouragementFromNeighbors(serverLevel, mutableTargetPos);
                  if (encouragementFromNeighbors <= 0) continue;

                  int airSpreadResistance = 100; // Base resistance for air spread
                  if (offsetY > 1) { // Harder to spread high into the air
                     airSpreadResistance += (offsetY - 1) * 100;
                  }

                  int difficulty = serverLevel.getDifficulty().getId();
                  // Chance for air to ignite, scaled by source fire age and neighbor encouragement
                  int igniteAirChanceNumerator = ((encouragementFromNeighbors + 40 + difficulty * 7) * SCALE_AGE) / (currentFireAge + 30 * SCALE_AGE);

                  if (isIncreasedBurnoutBiome) {
                     igniteAirChanceNumerator /= 2;
                  }

                  if (igniteAirChanceNumerator > 0 &&
                          randomSource.nextInt(airSpreadResistance) < igniteAirChanceNumerator &&
                          (!serverLevel.isRaining() || !this.isNearRain(serverLevel, mutableTargetPos))) {
                     serverLevel.setBlock(mutableTargetPos, this.getStateWithAge(serverLevel, mutableTargetPos, 0), Block.UPDATE_ALL); // New fire starts at age 0
                  }
               }
            }
         }
      }
   }

   protected boolean isNearRain(Level level, BlockPos pos) {
      return level.isRainingAt(pos) || level.isRainingAt(pos.west()) || level.isRainingAt(pos.east()) || level.isRainingAt(pos.north()) || level.isRainingAt(pos.south());
   }

   private int getFuelFlammability(BlockState fuelBlockState) { // Formerly getBlockBurnChance
      if (fuelBlockState.hasProperty(BlockStateProperties.WATERLOGGED) && fuelBlockState.getValue(BlockStateProperties.WATERLOGGED)) return 0;
      return this.burnOdds.getInt(fuelBlockState.getBlock()); // This is the key value for ROS from fuel type
   }

   private int getNeighborEncouragement(BlockState neighborBlockState) { // Formerly getBlockIgniteChance
      if (neighborBlockState.hasProperty(BlockStateProperties.WATERLOGGED) && neighborBlockState.getValue(BlockStateProperties.WATERLOGGED)) return 0;
      return this.igniteOdds.getInt(neighborBlockState.getBlock()); // How much this block encourages fire in adjacent air
   }

   /**
    * Attempts to spread fire to an adjacent fuel block.
    * This is the primary mechanism for surface fire spread.
    * Rate of Spread is heavily influenced by targetFuelFlammability (from burnOdds).
    */
   private void trySpreadToAdjacentFuel(Level level, BlockPos targetPos, int spreadResistanceParam, RandomSource random, int sourceFireAge) {
      // Probability Step 1: Does the fire overcome the general resistance and target's flammability?
      // P_Affect = targetFuelFlammability / spreadResistanceParam
      // Higher targetFuelFlammability (burnOdds) means higher chance.
      int targetFuelFlammability = this.getFuelFlammability(level.getBlockState(targetPos));
      if (targetFuelFlammability <= 0) return; // Target isn't flammable or burnable by this mechanism

      if (random.nextInt(spreadResistanceParam) < targetFuelFlammability) {
         BlockState targetBlockState = level.getBlockState(targetPos);

         // Probability Step 2: If affected, does it actually turn into fire?
         // This depends on the source fire's age (older fire might be "stronger").
         // P_Ignite_Given_Affected = (5 * SCALE_AGE) / (sourceFireAge + 10 * SCALE_AGE)
         boolean actuallyIgnites = random.nextInt(sourceFireAge + 10 * SCALE_AGE) < (5 * SCALE_AGE);

         if (actuallyIgnites && !level.isRainingAt(targetPos)) {
            // Fuel ignites and is replaced by a new fire block (age 0)
            level.setBlock(targetPos, this.getStateWithAge(level, targetPos, 0), Block.UPDATE_ALL);
         } else {
            // Fuel was affected but didn't sustain ignition (e.g., too "young" a source fire, or raining).
            // It gets consumed/removed to represent burnt fuel, allowing fire front to pass.
            // This is crucial for realistic isochrone development.
            level.removeBlock(targetPos, false);
         }

         if (targetBlockState.getBlock() instanceof TntBlock) { // TNT has special behavior
            TntBlock.explode(level, targetPos);
         }
      }
   }

   private BlockState getStateWithAge(LevelAccessor levelAccessor, BlockPos blockPos, int fireAge) {
      BlockState existingState = levelAccessor.getBlockState(blockPos);
      int newAge = Math.min(MAX_AGE, Math.max(0, fireAge));
      // If we're placing fire on a new spot (likely air or consumed block), or updating existing fire
      return this.defaultBlockState().setValue(AGE, newAge);
   }

   private boolean isValidFireLocation(BlockGetter blockGetter, BlockPos blockPos) {
      for (Direction direction : Direction.values()) {
         if (this.canBurn(blockGetter.getBlockState(blockPos.relative(direction)))) return true;
      }
      return false;
   }

   /**
    * Gets the highest "encouragement" value from neighbors of a potential air block fire.
    * Used by Mechanism 2 (3D Air Spread).
    */
   private int getEncouragementFromNeighbors(LevelReader levelReader, BlockPos airBlockPos) {
      if (!levelReader.isEmptyBlock(airBlockPos)) return 0; // Should be an air block

      int highestEncouragement = 0;
      for (Direction direction : Direction.values()) {
         BlockState neighborState = levelReader.getBlockState(airBlockPos.relative(direction));
         highestEncouragement = Math.max(highestEncouragement, this.getNeighborEncouragement(neighborState));
      }
      return highestEncouragement;
   }

   @Override
   protected boolean canBurn(BlockState potentialFuelState) {
      // A block is considered capable of burning (supporting fire / being a valid location)
      // if it has >0 "igniteOdds" (meaning it can encourage spread to air).
      // This is consistent with vanilla.
      return this.getNeighborEncouragement(potentialFuelState) > 0;
   }

   private static int getFireTickDelay(RandomSource randomSource) {
      return 20; // 1 second fixed delay
   }

   @Override
   protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
      builder.add(AGE, NORTH, EAST, SOUTH, WEST, UP);
   }

   private void setFlammable(Block block, int igniteOdds, int burnOdds) {
      this.igniteOdds.put(block, igniteOdds); // How well it encourages fire spread to adjacent AIR
      this.burnOdds.put(block, burnOdds);   // How readily IT BURNS and propagates fire to adjacent FUEL
   }

   public static void bootStrap() {
      FireBlock fireInstance = (FireBlock) Blocks.FIRE;

      // --- DEFINE FUEL PROPERTIES FOR QUADRANTS HERE ---
      // Higher burnOdds = faster spread on that fuel type.
      // spreadResistanceParam for horizontal spread is ~300 (less in wet biomes).
      // P(affect) approx. = burnOdds / 300.
      // E.g., burnOdds=30 -> ~10% chance per tick per neighbor.
      // E.g., burnOdds=150 -> ~50% chance per tick per neighbor.

      // Example for your 2D quadrant setup (adjust names and values as needed):
      // Quadrant 1: Fast Fuel (e.g., Dry Grass - use appropriate block type)
      // To make a block act like "FUEL_TYPE_FAST", assign these properties to it.
      fireInstance.setFlammable(Blocks.GRASS_BLOCK, 60, 150); // High burnOdds for fast spread
      fireInstance.setFlammable(Blocks.TALL_GRASS, 60, 180);  // Even faster
      fireInstance.setFlammable(Blocks.OAK_LEAVES, 30, 90);   // Leaves are also quite flammable


      fireInstance.setFlammable(Blocks.PURPLE_WOOL, 60, 180);   // Leaves are also quite flammable


      // Quadrant 2: Medium Fuel (e.g., Wooden Planks, Shrubbery)
      fireInstance.setFlammable(Blocks.OAK_PLANKS, 5, 30);   // Moderate burnOdds
      fireInstance.setFlammable(Blocks.OAK_LOG, 5, 25);      // Logs slightly slower than planks


      fireInstance.setFlammable(Blocks.GREEN_WOOL, 5, 30);      // Logs slightly slower than planks


      // Quadrant 3: Slow Fuel (e.g., Dense Wood, Compacted Material)
      // For a custom "Dense Fuel Block", you'd need to create that block first.
      // Using Wool as a proxy for something slower than planks but still flammable.
      fireInstance.setFlammable(Blocks.WHITE_WOOL, 5, 15);    // Slower burnOdds
      fireInstance.setFlammable(Blocks.BOOKSHELF, 30, 20);


      fireInstance.setFlammable(Blocks.PINK_WOOL, 5, 15);


      // Quadrant 4: Very Slow/Resistant Fuel (e.g., Damp Wood, Less Flammable Material)
      // Use a block that is barely flammable.
      // Example: Coal Block is very hard to burn in vanilla, used here as very slow.
      fireInstance.setFlammable(Blocks.COAL_BLOCK, 1, 5);   // Very low burnOdds


      fireInstance.setFlammable(Blocks.LIGHT_BLUE_WOOL, 1, 5);


      // --- Standard Flammability Settings (add more as needed) ---
      fireInstance.setFlammable(Blocks.SPRUCE_PLANKS, 5, 30);
      // ... (Paste your full list of other flammable blocks here) ...
      fireInstance.setFlammable(Blocks.TNT, 15, 100); // TNT is special, high burn to ensure it's affected
   }
}