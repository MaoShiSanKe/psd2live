// Runs a physics3.json through the official Cubism Native Framework on a real .moc3 and prints the
// driven parameters frame by frame, so PhysicsEngine can be checked against it (see README.md).
//
// Usage: physics_reference <model.moc3> <physics3.json> <schedule.csv>
// schedule.csv: a header "dt,<param>,<param>..." then one row per frame. Prints "frame,<output>..." rows,
// preceded by "# range,<id>,<min>,<max>,<default>" lines for every parameter named in either file.
#include <CubismFramework.hpp>
#include <Model/CubismMoc.hpp>
#include <Model/CubismModel.hpp>
#include <Physics/CubismPhysics.hpp>
#include <Id/CubismIdManager.hpp>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <set>
#include <sstream>
#include <string>
#include <vector>

using namespace Live2D::Cubism::Framework;

namespace {
class Allocator : public ICubismAllocator {
    void* Allocate(const csmSizeType size) override { return malloc(size); }
    void Deallocate(void* memory) override { free(memory); }
    void* AllocateAligned(const csmSizeType size, const csmUint32 alignment) override {
        void* p = nullptr;
        return posix_memalign(&p, alignment < sizeof(void*) ? sizeof(void*) : alignment, size) == 0 ? p : nullptr;
    }
    void DeallocateAligned(void* memory) override { free(memory); }
};

std::vector<csmByte> read(const char* path) {
    std::ifstream in(path, std::ios::binary);
    return std::vector<csmByte>((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

std::vector<std::string> split(const std::string& line) {
    std::vector<std::string> out;
    std::stringstream ss(line);
    std::string cell;
    while (std::getline(ss, cell, ',')) out.push_back(cell);
    return out;
}
}

int main(int argc, char** argv) {
    if (argc != 4) { std::cerr << "usage: physics_reference model.moc3 physics3.json schedule.csv\n"; return 2; }
    Allocator allocator;
    CubismFramework::Option option;
    option.LogFunction = nullptr;
    option.LoggingLevel = CubismFramework::Option::LogLevel_Off;
    CubismFramework::StartUp(&allocator, &option);
    CubismFramework::Initialize();

    auto mocBytes = read(argv[1]);
    CubismMoc* moc = CubismMoc::Create(mocBytes.data(), static_cast<csmSizeInt>(mocBytes.size()));
    if (!moc) { std::cerr << "cannot read moc3\n"; return 1; }
    CubismModel* model = moc->CreateModel();
    auto physicsBytes = read(argv[2]);
    CubismPhysics* physics = CubismPhysics::Create(physicsBytes.data(), static_cast<csmSizeInt>(physicsBytes.size()));
    if (!physics) { std::cerr << "cannot read physics3.json\n"; return 1; }

    std::ifstream schedule(argv[3]);
    std::string line;
    std::getline(schedule, line);
    std::vector<std::string> header = split(line);
    std::vector<std::string> inputs(header.begin() + 1, header.end());

    // Outputs: every parameter physics writes, read back after each frame.
    std::set<std::string> outputs;
    {
        std::string json(physicsBytes.begin(), physicsBytes.end());
        size_t at = 0;
        while ((at = json.find("\"Destination\"", at)) != std::string::npos) {
            size_t id = json.find("\"Id\"", at);
            size_t open = json.find('"', json.find(':', id) + 1);
            size_t close = json.find('"', open + 1);
            outputs.insert(json.substr(open + 1, close - open - 1));
            at = close;
        }
    }
    std::set<std::string> named(inputs.begin(), inputs.end());
    named.insert(outputs.begin(), outputs.end());
    for (const auto& id : named) {
        csmInt32 i = model->GetParameterIndex(CubismFramework::GetIdManager()->GetId(id.c_str()));
        printf("# range,%s,%.9g,%.9g,%.9g\n", id.c_str(), model->GetParameterMinimumValue(i), model->GetParameterMaximumValue(i),
            model->GetParameterDefaultValue(i));
    }
    printf("frame");
    for (const auto& id : outputs) printf(",%s", id.c_str());
    printf("\n");

    // As a Cubism app frame does: restore the saved parameters, pose them, save, then run physics.
    model->SaveParameters();
    int frame = 0;
    while (std::getline(schedule, line)) {
        std::vector<std::string> cells = split(line);
        if (cells.empty()) continue;
        float dt = std::stof(cells[0]);
        model->LoadParameters();
        for (size_t k = 0; k < inputs.size(); ++k) {
            model->SetParameterValue(CubismFramework::GetIdManager()->GetId(inputs[k].c_str()), std::stof(cells[k + 1]));
        }
        model->SaveParameters();
        physics->Evaluate(model, dt);
        printf("%d", frame++);
        for (const auto& id : outputs) printf(",%.9g", model->GetParameterValue(CubismFramework::GetIdManager()->GetId(id.c_str())));
        printf("\n");
    }
    CubismPhysics::Delete(physics);
    moc->DeleteModel(model);
    CubismMoc::Delete(moc);
    CubismFramework::Dispose();
    return 0;
}
