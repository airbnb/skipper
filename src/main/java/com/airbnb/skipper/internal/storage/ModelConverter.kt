package com.airbnb.skipper.internal.storage

/**
 * Interface for converting between a model object and a DTO object.
 *
 * @param <MODEL_TYPE> The type of the model object.
 * @param <DTO_TYPE> The type of the DTO object.
 */
interface ModelConverter<MODEL_TYPE, DTO_TYPE> {
    /**
     * Converts a model object to a DTO object.
     *
     * @param model The model object to convert.
     * @return The DTO object.
     */
    fun modelToDto(model: MODEL_TYPE): DTO_TYPE

    /**
     * Converts a DTO object to a model object.
     *
     * @param dto The DTO object to convert.
     * @return The model object.
     */
    fun dtoToModel(dto: DTO_TYPE): MODEL_TYPE
}
