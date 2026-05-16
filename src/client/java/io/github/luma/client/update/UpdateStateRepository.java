package io.github.luma.client.update;

public interface UpdateStateRepository {

    UpdateCheckState load();

    void save(UpdateCheckState state);
}
