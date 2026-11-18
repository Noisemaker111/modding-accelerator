package ${package}.mixin;

import ${package}.extensions.MiscHooks;
import net.minecraft.data.tags.TagsProvider;
import org.spongepowered.asm.mixin.Mixin;

/** Gives TagsProvider.TagAppender MiscHooks.TagAppenderTags (the interface's defaults are the methods). */
@Mixin(TagsProvider.TagAppender.class)
public abstract class TagAppenderTagsHooksMixin<T> implements MiscHooks.TagAppenderTags<T> {}
